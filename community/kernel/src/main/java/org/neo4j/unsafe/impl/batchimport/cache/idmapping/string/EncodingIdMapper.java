/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.unsafe.impl.batchimport.cache.idmapping.string;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.LongFunction;

import org.neo4j.function.Factory;
import org.neo4j.helpers.progress.ProgressListener;
import org.neo4j.unsafe.impl.batchimport.HighestId;
import org.neo4j.unsafe.impl.batchimport.InputIterable;
import org.neo4j.unsafe.impl.batchimport.Utils.CompareType;
import org.neo4j.unsafe.impl.batchimport.cache.ByteArray;
import org.neo4j.unsafe.impl.batchimport.cache.LongArray;
import org.neo4j.unsafe.impl.batchimport.cache.LongBitsManipulator;
import org.neo4j.unsafe.impl.batchimport.cache.MemoryStatsVisitor;
import org.neo4j.unsafe.impl.batchimport.cache.NumberArrayFactory;
import org.neo4j.unsafe.impl.batchimport.cache.idmapping.IdMapper;
import org.neo4j.unsafe.impl.batchimport.cache.idmapping.string.ParallelSort.Comparator;
import org.neo4j.unsafe.impl.batchimport.input.Collector;
import org.neo4j.unsafe.impl.batchimport.input.Group;
import org.neo4j.unsafe.impl.batchimport.input.Groups;
import org.neo4j.unsafe.impl.batchimport.input.InputException;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;

import static org.neo4j.helpers.Numbers.safeCastLongToInt;
import static org.neo4j.helpers.Numbers.safeCastLongToShort;
import static org.neo4j.unsafe.impl.batchimport.Utils.unsignedCompare;
import static org.neo4j.unsafe.impl.batchimport.Utils.unsignedDifference;
import static org.neo4j.unsafe.impl.batchimport.cache.idmapping.string.ParallelSort.DEFAULT;

/**
 * Maps arbitrary values to long ids. The values can be {@link #put(Object, long, Group) added} in any order,
 * but {@link #needsPreparation() needs} {@link #prepare(InputIterable, Collector, ProgressListener) preparation}
 *
 * in order to {@link #get(Object, Group) get} ids back later.
 *
 * In the {@link #prepare(LongFunction, Collector, ProgressListener) preparation phase} the added entries are
 * sorted according to a number representation of each input value and {@link #get(Object, Group)} does simple
 * binary search to find the correct one.
 *
 * The implementation is space-efficient, much more so than using, say, a {@link HashMap}.
 *
 * Terminology... there's a lot going on in here, and to help you understand the code here's a list
 * of terms used in comments and variable names and some description what each generally means
 * (also applies to {@link ParallelSort} btw):
 * - input id:
 *       An id coming from the user that is associated with a neo4j id by calling {@link #put(Object, long, Group)}.
 *       the first argument is the id that the user specified, the second is the neo4j id that user id will
 *       be associated with.
 * - encoder:
 *       Encodes an input id into an internal, more space efficient representation (a {@code long}) of that input id.
 * - eId:
 *       The internal representation of an input id, generated by an encoder.
 * - data cache:
 *       An array of eIds. eIds are added in the order of neo4j ids, i.e. in the order in which they are put.
 * - tracker cache:
 *       An array where every array item is a pointer to an index into the data cache it's set to track.
 *       After the data cache has been filled the eIds are sorted. This is done by _not_ sorting the data cache,
 *       but instead sorting the tracker cache as a proxy to its data cache. The reason it's done like this
 *       is that id spaces ({@link Group}) are kept as data cache ranges, since all ids for any given id space
 *       must be added together before adding any id for another id space.
 * - collision:
 *       Since eId has potentially fewer bits than an input id there's a chance multiple different (or equal)
 *       input ids will be encoded into the same eId. These are called collisions.
 */
public class EncodingIdMapper implements IdMapper
{
    public interface Monitor
    {
        /**
         * @param count Number of eIds that have been marked as collisions.
         */
        void numberOfCollisions( int count );
    }

    public static final Monitor NO_MONITOR = count ->
    {   // Do nothing.
    };

    // Bit in encoded String --> long values that marks that the particular item has a collision,
    // i.e. that there's at least one other string that encodes into the same long value.
    // This bit is the least significant in the most significant byte of the encoded values,
    // where the 7 most significant bits in that byte denotes length of original string.
    // See StringEncoder.
    private static LongBitsManipulator COLLISION_BIT = new LongBitsManipulator( 56, 1 );
    private static int DEFAULT_CACHE_CHUNK_SIZE = 1_000_000; // 8MB a piece
    // Using 0 as gap value, i.e. value for a node not having an id, i.e. not present in dataCache is safe
    // because the current set of Encoder implementations will always set some amount of bits higher up in
    // the long value representing the length of the id.
    private static final long GAP_VALUE = 0;
    private static final byte[] GROUP_CACHE_DEFAULT_VALUE = new byte[] {(byte) GAP_VALUE, (byte) GAP_VALUE};

    private final Factory<Radix> radixFactory;
    private final NumberArrayFactory cacheFactory;
    private final TrackerFactory trackerFactory;
    // Encoded values added in #put, in the order in which they are put. Indexes in the array are the actual node ids,
    // values are the encoded versions of the input ids.
    private final LongArray dataCache;
    private final ByteArray groupCache;
    private final HighestId candidateHighestSetIndex = new HighestId( -1 );
    private long highestSetIndex;

    // Ordering information about values in dataCache; the ordering of values in dataCache remains unchanged.
    // in prepare() this array is populated and changed along with how dataCache items "move around" so that
    // they end up sorted. Again, dataCache remains unchanged, only the ordering information is kept here.
    // Each index in trackerCache points to a dataCache index, where the value in dataCache contains the
    // encoded input id, used to match against the input id that is looked up during binary search.
    private Tracker trackerCache;
    private final Encoder encoder;
    private final Radix radix;
    private final int processorsForParallelWork;
    private final Comparator comparator;

    private final List<Object> collisionValues = new ArrayList<>();
    private final LongArray collisionNodeIdCache;
    // These 3 caches below are needed only during duplicate input id detection, but referenced here so
    // that the memory visitor can see them when they are active.
    private LongArray collisionSourceDataCache;
    private Tracker collisionTrackerCache;

    private boolean readyForUse;
    private long[][] sortBuckets;

    private final Monitor monitor;

    private int numberOfCollisions;
    private final Group[] groups = new Group[Groups.MAX_NUMBER_OF_GROUPS];

    public EncodingIdMapper( NumberArrayFactory cacheFactory, Encoder encoder, Factory<Radix> radixFactory,
            Monitor monitor, TrackerFactory trackerFactory )
    {
        this( cacheFactory, encoder, radixFactory, monitor, trackerFactory, DEFAULT_CACHE_CHUNK_SIZE,
                Runtime.getRuntime().availableProcessors() - 1, DEFAULT );
    }

    EncodingIdMapper( NumberArrayFactory cacheFactory, Encoder encoder, Factory<Radix> radixFactory,
            Monitor monitor, TrackerFactory trackerFactory, int chunkSize, int processorsForParallelWork,
            Comparator comparator )
    {
        this.radixFactory = radixFactory;
        this.monitor = monitor;
        this.cacheFactory = cacheFactory;
        this.trackerFactory = trackerFactory;
        this.comparator = comparator;
        this.processorsForParallelWork = max( processorsForParallelWork, 1 );
        this.dataCache = cacheFactory.newDynamicLongArray( chunkSize, GAP_VALUE );
        this.groupCache = cacheFactory.newDynamicByteArray( chunkSize, GROUP_CACHE_DEFAULT_VALUE );
        this.encoder = encoder;
        this.radix = radixFactory.newInstance();
        this.collisionNodeIdCache = cacheFactory.newDynamicLongArray( chunkSize, ID_NOT_FOUND );
    }

    /**
     * Returns the data index (i.e. node id) if found, or {@code -1} if not found.
     */
    @Override
    public long get( Object inputId, Group group )
    {
        assert readyForUse;
        return binarySearch( inputId, group.id() );
    }

    @Override
    public void put( Object inputId, long nodeId, Group group )
    {
        // Encode and add the input id
        long eId = encode( inputId );
        dataCache.set( nodeId, eId );
        groupCache.setShort( nodeId, 0, safeCastLongToShort( group.id() ) );
        candidateHighestSetIndex.offer( nodeId );

        // Store the group for later name lookup
        groups[group.id()] = group;
    }

    private long encode( Object inputId )
    {
        long eId = encoder.encode( inputId );
        if ( eId == GAP_VALUE )
        {
            throw new IllegalStateException( "Encoder " + encoder + " returned an illegal encoded value " + GAP_VALUE );
        }
        return eId;
    }

    @Override
    public boolean needsPreparation()
    {
        return true;
    }

    /**
     * There's an assumption that the progress listener supplied here can support multiple calls
     * to started/done, and that it knows about what stages the processor preparing goes through, namely:
     * <ol>
     * <li>Split by radix</li>
     * <li>Sorting</li>
     * <li>Collision detection</li>
     * <li>(potentially) Collision resolving</li>
     * </ol>
     */
    @Override
    public void prepare( LongFunction<Object> inputIdLookup, Collector collector, ProgressListener progress )
    {
        highestSetIndex = candidateHighestSetIndex.get();
        updateRadix( dataCache, radix, highestSetIndex );
        trackerCache = trackerFactory.create( cacheFactory, highestSetIndex + 1 );

        try
        {
            sortBuckets = new ParallelSort( radix, dataCache, highestSetIndex, trackerCache,
                    processorsForParallelWork, progress, comparator ).run();

            numberOfCollisions = detectAndMarkCollisions( progress, inputIdLookup );
            if ( numberOfCollisions > 0 )
            {
                buildCollisionInfo( inputIdLookup, collector, progress );
            }
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new RuntimeException( "Got interrupted while preparing the index. Throwing this exception "
                    + "onwards will cause a chain reaction which will cause a panic in the whole import, "
                    + "so mission accomplished" );
        }
        readyForUse = true;
    }

    private static void updateRadix( LongArray values, Radix radix, long highestSetIndex )
    {
        for ( long dataIndex = 0; dataIndex <= highestSetIndex; dataIndex++ )
        {
            radix.registerRadixOf( values.get( dataIndex ) );
        }
    }

    private int radixOf( long value )
    {
        return radix.calculator().radixOf( value );
    }

    private long binarySearch( Object inputId, int groupId )
    {
        long low = 0;
        long high = highestSetIndex;
        long x = encode( inputId );
        int rIndex = radixOf( x );
        for ( int k = 0; k < sortBuckets.length; k++ )
        {
            if ( rIndex <= sortBuckets[k][0] )//bucketRange[k] > rIndex )
            {
                low = sortBuckets[k][1];
                high = (k == sortBuckets.length - 1) ? highestSetIndex : sortBuckets[k + 1][1];
                break;
            }
        }

        long returnVal = binarySearch( x, inputId, low, high, groupId );
        if ( returnVal == ID_NOT_FOUND )
        {
            low = 0;
            high = highestSetIndex;
            returnVal = binarySearch( x, inputId, low, high, groupId );
        }
        return returnVal;
    }

    private static long setCollision( long eId )
    {
        return COLLISION_BIT.set( eId, 1, 1 );
    }

    static long clearCollision( long eId )
    {
        return COLLISION_BIT.clear( eId, 1, false );
    }

    private static boolean isCollision( long eId )
    {
        return COLLISION_BIT.get( eId, 1 ) != 0;
    }

    private class DetectWorker implements Runnable
    {
        private final long fromInclusive;
        private final long toExclusive;
        private final boolean last;
        private final ProgressListener progress;

        private int numberOfCollisions;
        private int localProgress;
        private final LongFunction<Object> inputIdLookup;

        DetectWorker( long fromInclusive, long toExclusive, boolean last, ProgressListener progress, LongFunction<Object> inputIdLookup )
        {
            this.fromInclusive = fromInclusive;
            this.toExclusive = toExclusive;
            this.last = last;
            this.progress = progress;
            this.inputIdLookup = inputIdLookup;
        }

        @Override
        public void run()
        {
            SameGroupDetector sameGroupDetector = new SameGroupDetector();

            // In all chunks except the last this chunk also takes care of the detection in the seam,
            // but for the last one there's no seam at the end.
            long end = last ? toExclusive - 1 : toExclusive;

            for ( long i = fromInclusive; i < end; i++ )
            {
                detect( sameGroupDetector, i );
                if ( ++localProgress == 1000 )
                {
                    progress.add( localProgress );
                    localProgress = 0;
                }
            }
            progress.add( localProgress );
        }

        private void detect( SameGroupDetector sameGroupDetector, long i )
        {
            long dataIndexA = trackerCache.get( i );
            long dataIndexB = trackerCache.get( i + 1 );
            if ( dataIndexA == ID_NOT_FOUND || dataIndexB == ID_NOT_FOUND )
            {
                sameGroupDetector.reset();
                return;
            }

            long eIdA = clearCollision( dataCache.get( dataIndexA ) );
            long eIdB = clearCollision( dataCache.get( dataIndexB ) );
            if ( eIdA == GAP_VALUE || eIdB == GAP_VALUE )
            {
                sameGroupDetector.reset();
                return;
            }

            switch ( unsignedDifference( eIdA, eIdB ) )
            {
            case GT: throw new IllegalStateException( "Unsorted data, a > b Failure:[" + i + "] " +
                    Long.toHexString( eIdA ) + " > " + Long.toHexString( eIdB ) + " | " +
                    radixOf( eIdA ) + ":" + radixOf( eIdB ) );
            case EQ:
                // Here we have two equal encoded values. First let's check if they are in the same id space.
                long collision = sameGroupDetector.collisionWithinSameGroup(
                        dataIndexA, groupOf( dataIndexA ),
                        dataIndexB, groupOf( dataIndexB ) );

                if ( dataIndexA > dataIndexB )
                {
                    // Swap so that lower tracker index means lower data index. TODO Why do we do this?
                    trackerCache.swap( i, i + 1 );
                }

                if ( collision != ID_NOT_FOUND )
                {
                    if ( markAsCollision( collision ) )
                    {
                        numberOfCollisions++;
                    }
                    if ( markAsCollision( dataIndexB ) )
                    {
                        numberOfCollisions++;
                    }
                }
                break;
            default:
                sameGroupDetector.reset();
            }
        }
    }

    /**
     * There are two types of collisions:
     * - actual: collisions coming from equal input value. These might however not impose
     *   keeping original input value since the colliding values might be for separate id groups,
     *   just as long as there's at most one per id space.
     * - accidental: collisions coming from different input values that happens to coerce into
     *   the same encoded value internally.
     *
     * For any encoded value there might be a mix of actual and accidental collisions. As long as there's
     * only one such value (accidental or actual) per id space the original input id doesn't need to be kept.
     * For scenarios where there are multiple per for any given id space:
     * - actual: there are two equal input values in the same id space
     *     ==> fail, not allowed
     * - accidental: there are two different input values coerced into the same encoded value
     *   in the same id space
     *     ==> original input values needs to be kept
     */
    private int detectAndMarkCollisions( ProgressListener progress, LongFunction<Object> inputIdLookup )
    {
        progress.started( "DETECT" );
        long totalCount = highestSetIndex + 1;

        Workers<DetectWorker> workers = new Workers<>( "DETECT" );
        int processors = processorsForParallelWork;
        long stride = totalCount / processorsForParallelWork;
        if ( stride < 10 )
        {
            // Multi-threading would be overhead
            processors = 1;
            stride = totalCount;
        }
        long fromInclusive = 0;
        long toExclusive = 0;
        for ( int i = 0; i < processors; i++ )
        {
            boolean last = i == processors - 1;
            fromInclusive = toExclusive;
            toExclusive = last ? totalCount : toExclusive + stride;
            workers.start( new DetectWorker( fromInclusive, toExclusive, last, progress, inputIdLookup ) );
        }
        workers.awaitAndThrowOnErrorStrict( RuntimeException.class );

        long numberOfCollisions = 0;
        for ( DetectWorker detectWorker : workers )
        {
            numberOfCollisions += detectWorker.numberOfCollisions;
        }

        progress.done();
        if ( numberOfCollisions > Integer.MAX_VALUE )
        {
            throw new InputException( "Too many collisions: " + numberOfCollisions );
        }

        int intNumberOfCollisions = toIntExact( numberOfCollisions );
        monitor.numberOfCollisions( intNumberOfCollisions );
        return intNumberOfCollisions;
    }

    /**
     * @return {@code true} if marked as collision in this call, {@code false} if it was already marked as collision.
     */
    private boolean markAsCollision( long nodeId )
    {
        long eId = dataCache.get( nodeId );
        boolean isAlreadyMarked = isCollision( eId );
        if ( isAlreadyMarked )
        {
            return false;
        }

        dataCache.set( nodeId, setCollision( eId ) );
        return true;
    }

    private void buildCollisionInfo( LongFunction<Object> inputIdLookup, Collector collector, ProgressListener progress )
            throws InterruptedException
    {
        progress.started( "RESOLVE (" + numberOfCollisions + " collisions)" );
        Radix radix = radixFactory.newInstance();
        collisionSourceDataCache = cacheFactory.newLongArray( numberOfCollisions, ID_NOT_FOUND );
        collisionTrackerCache = trackerFactory.create( cacheFactory, numberOfCollisions );
        for ( long nodeId = 0; nodeId <= highestSetIndex; nodeId++ )
        {
            long eId = dataCache.get( nodeId );
            if ( isCollision( eId ) )
            {
                // Store this collision input id for matching later in get()
                Object id = inputIdLookup.apply( nodeId );
                long eIdFromInputId = encode( id );
                long eIdWithoutCollisionBit = clearCollision( eId );
                assert eIdFromInputId == eIdWithoutCollisionBit : format( "Encoding mismatch during building of " +
                        "collision info. input id %s (a %s) marked as collision where this id was encoded into " +
                        "%d when put, but was now encoded into %d",
                        id, id.getClass().getSimpleName(), eIdWithoutCollisionBit, eIdFromInputId );
                int collisionIndex = collisionValues.size();
                collisionValues.add( id );
                collisionNodeIdCache.set( collisionIndex, nodeId );
                radix.registerRadixOf( eIdWithoutCollisionBit );
            }
            progress.add( 1 );
        }
        progress.done();

        // Detect input id duplicates within the same group, with source information, line number and the works
        detectDuplicateInputIds( radix, numberOfCollisions, collector, progress );

        // We won't be needing these anymore
        collisionSourceDataCache.close();
        collisionSourceDataCache = null;
        collisionTrackerCache.close();
        collisionTrackerCache = null;
    }

    private void detectDuplicateInputIds( Radix radix, int numberOfCollisions, Collector collector, ProgressListener progress )
            throws InterruptedException
    {
        // We do this collision sort using ParallelSort which has the data cache and the tracker cache,
        // the tracker cache gets sorted, data cache stays intact. In the collision data case we actually
        // have one more layer in here so we have tracker cache pointing to collisionNodeIdCache
        // pointing to dataCache. This can be done using the ParallelSort.Comparator abstraction.
        //
        // The Comparator below takes into account dataIndex for each eId its comparing so that an extra
        // comparison based on dataIndex is done if it's comparing two equal eIds. We do this so that
        // stretches of multiple equal eIds are sorted by dataIndex (i.e. node id) order,
        // to be able to write an efficient duplication scanning below and to have deterministic duplication reporting.
        Comparator duplicateComparator = new Comparator()
        {
            @Override
            public boolean lt( long left, long pivot )
            {
                long leftEId = dataCache.get( left );
                long pivotEId = dataCache.get( pivot );
                if ( comparator.lt( leftEId, pivotEId ) )
                {
                    return true;
                }
                if ( leftEId == pivotEId )
                {
                    return left < pivot;
                }
                return false;
            }

            @Override
            public boolean ge( long right, long pivot )
            {
                long rightEId = dataCache.get( right );
                long pivotEId = dataCache.get( pivot );
                if ( comparator.ge( rightEId, pivotEId ) )
                {
                    return rightEId != pivotEId || right > pivot;
                }
                return false;
            }

            @Override
            public long dataValue( long nodeId )
            {
                return dataCache.get( nodeId );
            }
        };

        new ParallelSort( radix, collisionNodeIdCache, numberOfCollisions - 1,
                collisionTrackerCache, processorsForParallelWork, progress, duplicateComparator ).run();

        // Here we have a populated C
        // We want to detect duplicate input ids within it
        long previousEid = 0;
        int previousGroupId = -1;
        SourceInformation source = new SourceInformation();
        SameInputIdDetector detector = new SameInputIdDetector();
        progress.started( "DEDUPLICATE" );
        for ( int i = 0; i < numberOfCollisions; i++ )
        {
            long collisionIndex = collisionTrackerCache.get( i );
            long nodeId = collisionNodeIdCache.get( collisionIndex );
            long eid = dataCache.get( nodeId );
            int groupId = groupOf( nodeId );
            // collisions of same eId AND groupId are always together
            boolean same = eid == previousEid && previousGroupId == groupId;
            if ( !same )
            {
                detector.clear();
            }

            // Potential duplicate
            // We cast the collision index to an int here. This means that we can't support > int-range
            // number of collisions. But that's probably alright since the data structures and
            // actual collisions values for all these collisions wouldn't fit in a heap anyway.
            Object inputId = collisionValues.get( safeCastLongToInt( collisionIndex ) );
            int detectorIndex = detector.add( inputId );
            if ( detectorIndex != -1 )
            {   // Duplicate
                collector.collectDuplicateNode( inputId, nodeId, groups[groupId].name() );
            }

            previousEid = eid;
            previousGroupId = groupId;
            progress.add( 1 );
        }
        progress.done();
    }

    private static class SameInputIdDetector
    {
        private Object[] inputIdArray = new Object[10]; // grows on demand
        private int cursor;
        private final SourceInformation source = new SourceInformation();

        int add( Object inputId )
        {
            for ( int i = 0; i < cursor; i++ )
            {
                if ( inputIdArray[i].equals( inputId ) )
                {
                    return i;
                }
            }

            if ( cursor == inputIdArray.length )
            {
                inputIdArray = Arrays.copyOf( inputIdArray, cursor * 2 );
            }
            inputIdArray[cursor] = inputId;
            cursor++;
            return -1;
        }

        void clear()
        {
            cursor = 0;
        }
    }

    private int groupOf( long dataIndex )
    {
        return groupCache.getShort( dataIndex, 0 );
    }

    private long binarySearch( long x, Object inputId, long low, long high, int groupId )
    {
        while ( low <= high )
        {
            long mid = low + (high - low) / 2;//(low + high) / 2;
            long dataIndex = trackerCache.get( mid );
            if ( dataIndex == ID_NOT_FOUND )
            {
                return ID_NOT_FOUND;
            }
            long midValue = dataCache.get( dataIndex );
            switch ( unsignedDifference( clearCollision( midValue ), x ) )
            {
            case EQ:
                // We found the value we were looking for. Question now is whether or not it's the only
                // of its kind. Not all values that there are duplicates of are considered collisions,
                // read more in detectAndMarkCollisions(). So regardless we need to check previous/next
                // if they are the same value.
                if ( (mid > 0 && unsignedCompare( x, dataValue( mid - 1 ), CompareType.EQ )) ||
                     (mid < highestSetIndex && unsignedCompare( x, dataValue( mid + 1 ), CompareType.EQ ) ) )
                {   // OK so there are actually multiple equal data values here, we need to go through them all
                    // to be sure we find the correct one.
                    return findFromEIdRange( mid, midValue, inputId, x, groupId );
                }
                // This is the only value here, let's do a simple comparison with correct group id and return
                return groupOf( dataIndex ) == groupId ? dataIndex : ID_NOT_FOUND;
            case LT:
                low = mid + 1;
                break;
            default:
                high = mid - 1;
                break;
            }
        }
        return ID_NOT_FOUND;
    }

    private long dataValue( long index )
    {
        return clearCollision( dataCache.get( trackerCache.get( index ) ) );
    }

    private long findCollisionIndex( long value )
    {
        // can't be done on unsorted data
        long low = 0 + 0;
        long high = numberOfCollisions - 1;
        while ( low <= high )
        {
            long mid = (low + high) / 2;
            long midValue = collisionNodeIdCache.get( mid );
            switch ( unsignedDifference( midValue, value ) )
            {
            case EQ: return mid;
            case LT:
                low = mid + 1;
                break;
            default:
                high = mid - 1;
                break;
            }
        }
        return ID_NOT_FOUND;
    }

    private long findFromEIdRange( long index, long val, Object inputId, long x, int groupId )
    {
        val = clearCollision( val );
        assert val == x;

        while ( index > 0 && unsignedCompare( val, dataValue( index - 1 ), CompareType.EQ ) )
        {
            index--;
        }
        long fromIndex = index;
        while ( index < highestSetIndex && unsignedCompare( val, dataValue( index + 1 ), CompareType.EQ ) )
        {
            index++;
        }
        long toIndex = index;

        return findFromEIdRange( fromIndex, toIndex, groupId, inputId );
    }

    private long findFromEIdRange( long fromIndex, long toIndex, int groupId, Object inputId )
    {
        long lowestFound = ID_NOT_FOUND; // lowest data index means "first put"
        for ( long index = fromIndex; index <= toIndex; index++ )
        {
            long nodeId = trackerCache.get( index );
            int group = groupOf( nodeId );
            if ( groupId == group )
            {
                long eId = dataCache.get( nodeId );
                if ( isCollision( eId ) )
                {   // We found a data value for our group, but there are collisions within this group.
                    // We need to consult the collision cache and original input id
                    int collisionIndex = safeCastLongToInt( findCollisionIndex( nodeId ) );
                    Object value = collisionValues.get( collisionIndex );
                    if ( inputId.equals( value ) )
                    {
                        // :)
                        lowestFound = lowestFound == ID_NOT_FOUND ? nodeId : min( lowestFound, nodeId );
                        // continue checking so that we can find the lowest one. It's not up to us here to
                        // consider multiple equal ids in this group an error or not. That should have been
                        // decided in #prepare.
                    }
                }
                else
                {   // We found a data value that is alone in its group. Just return it
                    // :D
                    lowestFound = nodeId;

                    // We don't need to look no further because this value wasn't a collision,
                    // i.e. there are more like it for this group
                    break;
                }
            }
        }
        return lowestFound;
    }

    @Override
    public void acceptMemoryStatsVisitor( MemoryStatsVisitor visitor )
    {
        nullSafeAcceptMemoryStatsVisitor( visitor, dataCache );
        nullSafeAcceptMemoryStatsVisitor( visitor, trackerCache );
        nullSafeAcceptMemoryStatsVisitor( visitor, collisionTrackerCache );
        nullSafeAcceptMemoryStatsVisitor( visitor, collisionSourceDataCache );
        nullSafeAcceptMemoryStatsVisitor( visitor, collisionNodeIdCache );
    }

    private void nullSafeAcceptMemoryStatsVisitor( MemoryStatsVisitor visitor, MemoryStatsVisitor.Visitable mem )
    {
        if ( mem != null )
        {
            mem.acceptMemoryStatsVisitor( visitor );
        }
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "[" + encoder + "," + radix + "]";
    }

    @Override
    public void close()
    {
        dataCache.close();
        if ( trackerCache != null )
        {
            trackerCache.close();
        }
        if ( collisionSourceDataCache != null )
        {
            collisionTrackerCache.close();
            collisionSourceDataCache.close();
        }
        collisionNodeIdCache.close();
    }

    @Override
    public long calculateMemoryUsage( long numberOfNodes )
    {
        int trackerSize = numberOfNodes > TrackerFactories.HIGHEST_ID_FOR_SMALL_TRACKER ? BigIdTracker.ID_SIZE : IntTracker.ID_SIZE;
        return numberOfNodes * (Long.BYTES /*data*/ + trackerSize /*tracker*/);
    }
}
