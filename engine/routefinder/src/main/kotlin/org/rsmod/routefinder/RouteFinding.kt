package org.rsmod.routefinder

import java.util.Arrays
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.collision.CollisionStrategy
import org.rsmod.routefinder.flag.CollisionFlag
import org.rsmod.routefinder.flag.CollisionFlag.BLOCK_NORTH_AND_SOUTH_EAST
import org.rsmod.routefinder.flag.CollisionFlag.BLOCK_NORTH_AND_SOUTH_EAST_ROUTE_BLOCKER
import org.rsmod.routefinder.flag.CollisionFlag.BLOCK_NORTH_AND_SOUTH_WEST
import org.rsmod.routefinder.flag.CollisionFlag.BLOCK_NORTH_EAST
import org.rsmod.routefinder.flag.CollisionFlag.BLOCK_NORTH_EAST_AND_WEST_ROUTE_BLOCKER
import org.rsmod.routefinder.flag.CollisionFlag.BLOCK_NORTH_WEST
import org.rsmod.routefinder.flag.CollisionFlag.BLOCK_NORTH_WEST_ROUTE_BLOCKER
import org.rsmod.routefinder.flag.CollisionFlag.BLOCK_SOUTH_EAST
import org.rsmod.routefinder.flag.CollisionFlag.BLOCK_SOUTH_EAST_AND_WEST
import org.rsmod.routefinder.flag.CollisionFlag.BLOCK_SOUTH_EAST_ROUTE_BLOCKER
import org.rsmod.routefinder.flag.DirectionFlag
import org.rsmod.routefinder.reach.ReachStrategy
import org.rsmod.routefinder.util.Rotations.rotate

private const val DEFAULT_SEARCH_MAP_SIZE = 128
private const val DEFAULT_RING_BUFFER_SIZE = 4096
private const val DEFAULT_DISTANCE_VALUE = 99_999_999
private const val DEFAULT_SRC_DIRECTION_VALUE = 99
private const val MAX_ALTERNATIVE_ROUTE_LOWEST_COST = 1000
private const val MAX_ALTERNATIVE_ROUTE_SEEK_RANGE = 100
private const val MAX_ALTERNATIVE_ROUTE_DISTANCE_FROM_DESTINATION = 10
private const val DEFAULT_USE_ROUTE_BLOCKER_FLAGS = false

public class RouteFinding(
    private val flags: CollisionFlagMap,
    private val searchMapSize: Int = DEFAULT_SEARCH_MAP_SIZE,
    private val ringBufferSize: Int = DEFAULT_RING_BUFFER_SIZE,
    private val useRouteBlockerFlags: Boolean = DEFAULT_USE_ROUTE_BLOCKER_FLAGS,
) {
    private val directions = IntArray(searchMapSize * searchMapSize)
    private val distances = IntArray(searchMapSize * searchMapSize) { DEFAULT_DISTANCE_VALUE }
    private val validLocalX = IntArray(ringBufferSize)
    private val validLocalZ = IntArray(ringBufferSize)
    private var currLocalX = 0
    private var currLocalZ = 0
    private var bufReaderIndex = 0
    private var bufWriterIndex = 0

    /**
     * Creates a validated [Route] from ([srcX], [srcZ]) to ([destX], [destZ]) on height [level]
     * avoiding obstacles in the appropriate manner respective to the given [collision] strategy.
     *
     * @param destWidth the _absolute_ width of the destination. This value should _not_ be changed
     *   when passing the width of a rotated loc. (it is done for us within the function)
     * @param destLength the _absolute_ length of the destination. Similar to [destWidth], this
     *   value should _not_ be changed or altered for rotated locs.
     * @param locAngle the angle of the target loc being used as the destination. If the path is
     *   meant for something that is _not_ a loc, this value should be passed or left as the
     *   default 0.
     * @param locShape the shape of the target loc being used as the destination. If the path is
     *   meant for something that is _not_ a loc, this value should be passed or left as the default
     *   -1.
     * @param blockAccessFlags packed directional bitflags that should be blocked off when a "reach
     *   strategy" (AKA exit strategy) is checked. This can be seen in locs such as staircases,
     *   where all directions excluding the direction with access to the steps are "blocked." (see
     *   [org.rsmod.routefinder.flag.BlockAccessFlag])
     * @return [Route.FAILED] if there is no valid path from source to destination coordinates. If
     *   validated route could _not_ reach destination, but [moveNear] flag is enabled and
     *   [findClosestApproachPoint] returns true - route is returned with [Route.alternative] as
     *   true, [Route.success] as false and a partial [Route.waypoints] list. Otherwise, if route
     *   successfully reaches the destination - route is returned with [Route.success] as true,
     *   [Route.alternative] as false and a full [Route.waypoints] list.
     * @throws IllegalArgumentException if coordinates are out of bounds. `x` and `z` cannot be over
     *   14-bits - 1, or a value of 16383. `level` cannot be over 2-bits - 1, or a value of 3.
     */
    public fun findRoute(
        level: Int,
        srcX: Int,
        srcZ: Int,
        destX: Int,
        destZ: Int,
        srcSize: Int = 1,
        destWidth: Int = 1,
        destLength: Int = 1,
        locAngle: Int = 0,
        locShape: Int = -1,
        moveNear: Boolean = true,
        blockAccessFlags: Int = 0,
        maxWaypoints: Int = 25,
        collision: CollisionStrategy = CollisionStrategy.Normal,
    ): Route {
        require(srcX in 0..0x7FFF && srcZ in 0..0x7FFF)
        require(destX in 0..0x7FFF && destZ in 0..0x7FFF)
        require(level in 0..0x3)
        reset()
        val baseX = srcX - (searchMapSize / 2)
        val baseZ = srcZ - (searchMapSize / 2)
        val localSrcX = srcX - baseX
        val localSrcZ = srcZ - baseZ
        val localDestX = destX - baseX
        val localDestZ = destZ - baseZ
        appendDirection(localSrcX, localSrcZ, DEFAULT_SRC_DIRECTION_VALUE, 0)
        val pathFound: Boolean =
            if (useRouteBlockerFlags) {
                when (srcSize) {
                    1 ->
                        routeBlockerFindSize1(
                            baseX,
                            baseZ,
                            level,
                            localDestX,
                            localDestZ,
                            destWidth,
                            destLength,
                            srcSize,
                            locAngle,
                            locShape,
                            blockAccessFlags,
                            collision,
                        )
                    2 ->
                        routeBlockerFindSize2(
                            baseX,
                            baseZ,
                            level,
                            localDestX,
                            localDestZ,
                            destWidth,
                            destLength,
                            srcSize,
                            locAngle,
                            locShape,
                            blockAccessFlags,
                            collision,
                        )
                    else ->
                        routeBlockerFindSizeBig(
                            baseX,
                            baseZ,
                            level,
                            localDestX,
                            localDestZ,
                            destWidth,
                            destLength,
                            srcSize,
                            locAngle,
                            locShape,
                            blockAccessFlags,
                            collision,
                        )
                }
            } else {
                when (srcSize) {
                    1 ->
                        routeFindSize1(
                            baseX,
                            baseZ,
                            level,
                            localDestX,
                            localDestZ,
                            destWidth,
                            destLength,
                            srcSize,
                            locAngle,
                            locShape,
                            blockAccessFlags,
                            collision,
                        )
                    2 ->
                        routeFindSize2(
                            baseX,
                            baseZ,
                            level,
                            localDestX,
                            localDestZ,
                            destWidth,
                            destLength,
                            srcSize,
                            locAngle,
                            locShape,
                            blockAccessFlags,
                            collision,
                        )
                    else ->
                        routeFindBig(
                            baseX,
                            baseZ,
                            level,
                            localDestX,
                            localDestZ,
                            destWidth,
                            destLength,
                            srcSize,
                            locAngle,
                            locShape,
                            blockAccessFlags,
                            collision,
                        )
                }
            }
        if (!pathFound) {
            if (!moveNear) {
                return Route.FAILED
            }
            val foundApproachPoint =
                findClosestApproachPoint(
                    localDestX,
                    localDestZ,
                    rotate(locAngle, destWidth, destLength),
                    rotate(locAngle, destLength, destWidth),
                )
            if (!foundApproachPoint) {
                return Route.FAILED
            }
        }
        val waypoints = ArrayDeque<RouteCoordinates>(maxWaypoints + 1)
        var nextDir = directions[currLocalX, currLocalZ]
        var currDir = -1
        for (i in directions.indices) {
            if (currLocalX == localSrcX && currLocalZ == localSrcZ) {
                break
            }
            if (currDir != nextDir) {
                currDir = nextDir
                if (waypoints.size >= maxWaypoints) waypoints.removeLast()
                val coords = RouteCoordinates(baseX + currLocalX, baseZ + currLocalZ, level)
                waypoints.addFirst(coords)
            }
            if ((currDir and DirectionFlag.EAST) != 0) {
                currLocalX++
            } else if ((currDir and DirectionFlag.WEST) != 0) {
                currLocalX--
            }
            if ((currDir and DirectionFlag.NORTH) != 0) {
                currLocalZ++
            } else if ((currDir and DirectionFlag.SOUTH) != 0) {
                currLocalZ--
            }
            nextDir = directions[currLocalX, currLocalZ]
        }
        return Route(waypoints = waypoints, alternative = !pathFound, success = true)
    }

    private fun routeFindSize1(
        baseX: Int,
        baseZ: Int,
        level: Int,
        localDestX: Int,
        localDestZ: Int,
        destWidth: Int,
        destLength: Int,
        srcSize: Int,
        locAngle: Int,
        locShape: Int,
        blockAccessFlags: Int,
        collision: CollisionStrategy,
    ): Boolean {
        var x: Int
        var z: Int
        var clipFlag: Int
        var dirFlag: Int
        val relativeSearchSize = searchMapSize - 1
        while (bufWriterIndex != bufReaderIndex) {
            currLocalX = validLocalX[bufReaderIndex]
            currLocalZ = validLocalZ[bufReaderIndex]
            bufReaderIndex = (bufReaderIndex + 1) and (ringBufferSize - 1)

            if (
                ReachStrategy.reached(
                    flags = flags,
                    level = level,
                    srcX = currLocalX + baseX,
                    srcZ = currLocalZ + baseZ,
                    destX = localDestX + baseX,
                    destZ = localDestZ + baseZ,
                    destWidth = destWidth,
                    destLength = destLength,
                    srcSize = srcSize,
                    locAngle = locAngle,
                    locShape = locShape,
                    blockAccessFlags = blockAccessFlags,
                )
            ) {
                return true
            }

            val nextDistance = distances[currLocalX, currLocalZ] + 1

            /* east to west */
            x = currLocalX - 1
            z = currLocalZ
            clipFlag = CollisionFlag.BLOCK_WEST
            dirFlag = DirectionFlag.EAST
            if (
                currLocalX > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(flags[baseX, baseZ, x, z, level], clipFlag)
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* west to east */
            x = currLocalX + 1
            z = currLocalZ
            clipFlag = CollisionFlag.BLOCK_EAST
            dirFlag = DirectionFlag.WEST
            if (
                currLocalX < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(flags[baseX, baseZ, x, z, level], clipFlag)
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* north to south  */
            x = currLocalX
            z = currLocalZ - 1
            clipFlag = CollisionFlag.BLOCK_SOUTH
            dirFlag = DirectionFlag.NORTH
            if (
                currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(flags[baseX, baseZ, x, z, level], clipFlag)
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* south to north */
            x = currLocalX
            z = currLocalZ + 1
            clipFlag = CollisionFlag.BLOCK_NORTH
            dirFlag = DirectionFlag.SOUTH
            if (
                currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(flags[baseX, baseZ, x, z, level], clipFlag)
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* north-east to south-west */
            x = currLocalX - 1
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH_EAST
            if (
                currLocalX > 0 &&
                    currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ, level],
                        CollisionFlag.BLOCK_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX, z, level],
                        CollisionFlag.BLOCK_SOUTH,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* north-west to south-east */
            x = currLocalX + 1
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH_WEST
            if (
                currLocalX < relativeSearchSize &&
                    currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(flags[baseX, baseZ, x, z, level], BLOCK_SOUTH_EAST) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ, level],
                        CollisionFlag.BLOCK_EAST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX, z, level],
                        CollisionFlag.BLOCK_SOUTH,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* south-east to north-west */
            x = currLocalX - 1
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH_EAST
            if (
                currLocalX > 0 &&
                    currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(flags[baseX, baseZ, x, z, level], BLOCK_NORTH_WEST) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ, level],
                        CollisionFlag.BLOCK_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX, z, level],
                        CollisionFlag.BLOCK_NORTH,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* south-west to north-east */
            x = currLocalX + 1
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH_WEST
            if (
                currLocalX < relativeSearchSize &&
                    currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(flags[baseX, baseZ, x, z, level], BLOCK_NORTH_EAST) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ, level],
                        CollisionFlag.BLOCK_EAST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX, z, level],
                        CollisionFlag.BLOCK_NORTH,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }
        }
        return false
    }

    private fun routeFindSize2(
        baseX: Int,
        baseZ: Int,
        level: Int,
        localDestX: Int,
        localDestZ: Int,
        destWidth: Int,
        destLength: Int,
        srcSize: Int,
        locAngle: Int,
        locShape: Int,
        blockAccessFlags: Int,
        collision: CollisionStrategy,
    ): Boolean {
        var x: Int
        var z: Int
        var dirFlag: Int
        val relativeSearchSize = searchMapSize - 2
        while (bufWriterIndex != bufReaderIndex) {
            currLocalX = validLocalX[bufReaderIndex]
            currLocalZ = validLocalZ[bufReaderIndex]
            bufReaderIndex = (bufReaderIndex + 1) and (ringBufferSize - 1)

            if (
                ReachStrategy.reached(
                    flags = flags,
                    level = level,
                    srcX = currLocalX + baseX,
                    srcZ = currLocalZ + baseZ,
                    destX = localDestX + baseX,
                    destZ = localDestZ + baseZ,
                    destWidth = destWidth,
                    destLength = destLength,
                    srcSize = srcSize,
                    locAngle = locAngle,
                    locShape = locShape,
                    blockAccessFlags = blockAccessFlags,
                )
            ) {
                return true
            }

            val nextDistance = distances[currLocalX, currLocalZ] + 1

            /* east to west */
            x = currLocalX - 1
            z = currLocalZ
            dirFlag = DirectionFlag.EAST
            if (
                currLocalX > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + 1, level],
                        BLOCK_NORTH_WEST,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* west to east */
            x = currLocalX + 1
            z = currLocalZ
            dirFlag = DirectionFlag.WEST
            if (
                currLocalX < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 2, z, level],
                        BLOCK_SOUTH_EAST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 2, currLocalZ + 1, level],
                        BLOCK_NORTH_EAST,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* north to south  */
            x = currLocalX
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH
            if (
                currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 1, z, level],
                        BLOCK_SOUTH_EAST,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* south to north */
            x = currLocalX
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH
            if (
                currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + 2, level],
                        BLOCK_NORTH_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 1, currLocalZ + 2, level],
                        BLOCK_NORTH_EAST,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* north-east to south-west */
            x = currLocalX - 1
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH_EAST
            if (
                currLocalX > 0 &&
                    currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ, level],
                        BLOCK_NORTH_AND_SOUTH_EAST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX, z, level],
                        CollisionFlag.BLOCK_NORTH_EAST_AND_WEST,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* north-west to south-east */
            x = currLocalX + 1
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH_WEST
            if (
                currLocalX < relativeSearchSize &&
                    currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_NORTH_EAST_AND_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 2, z, level],
                        BLOCK_SOUTH_EAST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 2, currLocalZ, level],
                        BLOCK_NORTH_AND_SOUTH_WEST,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* south-east to north-west */
            x = currLocalX - 1
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH_EAST
            if (
                currLocalX > 0 &&
                    currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        BLOCK_NORTH_AND_SOUTH_EAST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + 2, level],
                        BLOCK_NORTH_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX, currLocalZ + 2, level],
                        BLOCK_SOUTH_EAST_AND_WEST,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* south-west to north-east */
            x = currLocalX + 1
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH_WEST
            if (
                currLocalX < relativeSearchSize &&
                    currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + 2, level],
                        BLOCK_SOUTH_EAST_AND_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 2, currLocalZ + 2, level],
                        BLOCK_NORTH_EAST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 2, z, level],
                        BLOCK_NORTH_AND_SOUTH_WEST,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }
        }
        return false
    }

    private fun routeFindBig(
        baseX: Int,
        baseZ: Int,
        level: Int,
        localDestX: Int,
        localDestZ: Int,
        destWidth: Int,
        destLength: Int,
        srcSize: Int,
        locAngle: Int,
        locShape: Int,
        blockAccessFlags: Int,
        collision: CollisionStrategy,
    ): Boolean {
        var x: Int
        var z: Int
        var dirFlag: Int
        val relativeSearchSize = searchMapSize - srcSize
        while (bufWriterIndex != bufReaderIndex) {
            currLocalX = validLocalX[bufReaderIndex]
            currLocalZ = validLocalZ[bufReaderIndex]
            bufReaderIndex = (bufReaderIndex + 1) and (ringBufferSize - 1)

            if (
                ReachStrategy.reached(
                    flags = flags,
                    level = level,
                    srcX = currLocalX + baseX,
                    srcZ = currLocalZ + baseZ,
                    destX = localDestX + baseX,
                    destZ = localDestZ + baseZ,
                    destWidth = destWidth,
                    destLength = destLength,
                    srcSize = srcSize,
                    locAngle = locAngle,
                    locShape = locShape,
                    blockAccessFlags = blockAccessFlags,
                )
            ) {
                return true
            }

            val nextDistance = distances[currLocalX, currLocalZ] + 1

            /* east to west */
            x = currLocalX - 1
            z = currLocalZ
            dirFlag = DirectionFlag.EAST
            if (
                currLocalX > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + srcSize - 1, level],
                        BLOCK_NORTH_WEST,
                    )
            ) {
                val clipFlag = BLOCK_NORTH_AND_SOUTH_EAST
                val blocked =
                    (1 until srcSize - 1).any {
                        !collision.canMove(flags[baseX, baseZ, x, currLocalZ + it, level], clipFlag)
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* west to east */
            x = currLocalX + 1
            z = currLocalZ
            dirFlag = DirectionFlag.WEST
            if (
                currLocalX < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + srcSize, z, level],
                        BLOCK_SOUTH_EAST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + srcSize, currLocalZ + srcSize - 1, level],
                        BLOCK_NORTH_EAST,
                    )
            ) {
                val clipFlag = BLOCK_NORTH_AND_SOUTH_WEST
                val blocked =
                    (1 until srcSize - 1).any {
                        !collision.canMove(
                            flags[baseX, baseZ, currLocalX + srcSize, currLocalZ + it, level],
                            clipFlag,
                        )
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* north to south  */
            x = currLocalX
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH
            if (
                currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + srcSize - 1, z, level],
                        BLOCK_SOUTH_EAST,
                    )
            ) {
                val clipFlag = CollisionFlag.BLOCK_NORTH_EAST_AND_WEST
                val blocked =
                    (1 until srcSize - 1).any {
                        !collision.canMove(flags[baseX, baseZ, currLocalX + it, z, level], clipFlag)
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* south to north */
            x = currLocalX
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH
            if (
                currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + srcSize, level],
                        BLOCK_NORTH_WEST,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + srcSize - 1, currLocalZ + srcSize, level],
                        BLOCK_NORTH_EAST,
                    )
            ) {
                val clipFlag = BLOCK_SOUTH_EAST_AND_WEST
                val blocked =
                    (1 until srcSize - 1).any {
                        !collision.canMove(
                            flags[baseX, baseZ, x + it, currLocalZ + srcSize, level],
                            clipFlag,
                        )
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* north-east to south-west */
            x = currLocalX - 1
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH_EAST
            if (
                currLocalX > 0 &&
                    currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST,
                    )
            ) {
                val clipFlag1 = BLOCK_NORTH_AND_SOUTH_EAST
                val clipFlag2 = CollisionFlag.BLOCK_NORTH_EAST_AND_WEST
                val blocked =
                    (1 until srcSize).any {
                        !collision.canMove(
                            flags[baseX, baseZ, x, currLocalZ + it - 1, level],
                            clipFlag1,
                        ) ||
                            !collision.canMove(
                                flags[baseX, baseZ, currLocalX + it - 1, z, level],
                                clipFlag2,
                            )
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* north-west to south-east */
            x = currLocalX + 1
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH_WEST
            if (
                currLocalX < relativeSearchSize &&
                    currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + srcSize, z, level],
                        BLOCK_SOUTH_EAST,
                    )
            ) {
                val clipFlag1 = BLOCK_NORTH_AND_SOUTH_WEST
                val clipFlag2 = CollisionFlag.BLOCK_NORTH_EAST_AND_WEST
                val blocked =
                    (1 until srcSize).any {
                        !collision.canMove(
                            flags[baseX, baseZ, currLocalX + srcSize, currLocalZ + it - 1, level],
                            clipFlag1,
                        ) ||
                            !collision.canMove(
                                flags[baseX, baseZ, currLocalX + it, z, level],
                                clipFlag2,
                            )
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* south-east to north-west */
            x = currLocalX - 1
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH_EAST
            if (
                currLocalX > 0 &&
                    currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + srcSize, level],
                        BLOCK_NORTH_WEST,
                    )
            ) {
                val clipFlag1 = BLOCK_NORTH_AND_SOUTH_EAST
                val clipFlag2 = BLOCK_SOUTH_EAST_AND_WEST
                val blocked =
                    (1 until srcSize).any {
                        !collision.canMove(
                            flags[baseX, baseZ, x, currLocalZ + it, level],
                            clipFlag1,
                        ) ||
                            !collision.canMove(
                                flags[
                                    baseX, baseZ, currLocalX + it - 1, currLocalZ + srcSize, level],
                                clipFlag2,
                            )
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* south-west to north-east */
            x = currLocalX + 1
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH_WEST
            if (
                currLocalX < relativeSearchSize &&
                    currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + srcSize, currLocalZ + srcSize, level],
                        BLOCK_NORTH_EAST,
                    )
            ) {
                val clipFlag1 = BLOCK_SOUTH_EAST_AND_WEST
                val clipFlag2 = BLOCK_NORTH_AND_SOUTH_WEST
                val blocked =
                    (1 until srcSize).any {
                        !collision.canMove(
                            flags[baseX, baseZ, currLocalX + it, currLocalZ + srcSize, level],
                            clipFlag1,
                        ) ||
                            !collision.canMove(
                                flags[baseX, baseZ, currLocalX + srcSize, currLocalZ + it, level],
                                clipFlag2,
                            )
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }
        }
        return false
    }

    private fun routeBlockerFindSize1(
        baseX: Int,
        baseZ: Int,
        level: Int,
        localDestX: Int,
        localDestZ: Int,
        destWidth: Int,
        destLength: Int,
        srcSize: Int,
        locAngle: Int,
        locShape: Int,
        blockAccessFlags: Int,
        collision: CollisionStrategy,
    ): Boolean {
        var x: Int
        var z: Int
        var clipFlag: Int
        var dirFlag: Int
        val relativeSearchSize = searchMapSize - 1
        while (bufWriterIndex != bufReaderIndex) {
            currLocalX = validLocalX[bufReaderIndex]
            currLocalZ = validLocalZ[bufReaderIndex]
            bufReaderIndex = (bufReaderIndex + 1) and (ringBufferSize - 1)

            if (
                ReachStrategy.reached(
                    flags = flags,
                    level = level,
                    srcX = currLocalX + baseX,
                    srcZ = currLocalZ + baseZ,
                    destX = localDestX + baseX,
                    destZ = localDestZ + baseZ,
                    destWidth = destWidth,
                    destLength = destLength,
                    srcSize = srcSize,
                    locAngle = locAngle,
                    locShape = locShape,
                    blockAccessFlags = blockAccessFlags,
                )
            ) {
                return true
            }

            val nextDistance = distances[currLocalX, currLocalZ] + 1

            /* east to west */
            x = currLocalX - 1
            z = currLocalZ
            clipFlag = CollisionFlag.BLOCK_WEST_ROUTE_BLOCKER
            dirFlag = DirectionFlag.EAST
            if (
                currLocalX > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(flags[baseX, baseZ, x, z, level], clipFlag)
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* west to east */
            x = currLocalX + 1
            z = currLocalZ
            clipFlag = CollisionFlag.BLOCK_EAST_ROUTE_BLOCKER
            dirFlag = DirectionFlag.WEST
            if (
                currLocalX < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(flags[baseX, baseZ, x, z, level], clipFlag)
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* north to south  */
            x = currLocalX
            z = currLocalZ - 1
            clipFlag = CollisionFlag.BLOCK_SOUTH_ROUTE_BLOCKER
            dirFlag = DirectionFlag.NORTH
            if (
                currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(flags[baseX, baseZ, x, z, level], clipFlag)
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* south to north */
            x = currLocalX
            z = currLocalZ + 1
            clipFlag = CollisionFlag.BLOCK_NORTH_ROUTE_BLOCKER
            dirFlag = DirectionFlag.SOUTH
            if (
                currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(flags[baseX, baseZ, x, z, level], clipFlag)
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* north-east to south-west */
            x = currLocalX - 1
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH_EAST
            if (
                currLocalX > 0 &&
                    currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ, level],
                        CollisionFlag.BLOCK_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX, z, level],
                        CollisionFlag.BLOCK_SOUTH_ROUTE_BLOCKER,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* north-west to south-east */
            x = currLocalX + 1
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH_WEST
            if (
                currLocalX < relativeSearchSize &&
                    currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        BLOCK_SOUTH_EAST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ, level],
                        CollisionFlag.BLOCK_EAST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX, z, level],
                        CollisionFlag.BLOCK_SOUTH_ROUTE_BLOCKER,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* south-east to north-west */
            x = currLocalX - 1
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH_EAST
            if (
                currLocalX > 0 &&
                    currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        BLOCK_NORTH_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ, level],
                        CollisionFlag.BLOCK_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX, z, level],
                        CollisionFlag.BLOCK_NORTH_ROUTE_BLOCKER,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* south-west to north-east */
            x = currLocalX + 1
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH_WEST
            if (
                currLocalX < relativeSearchSize &&
                    currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_NORTH_EAST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ, level],
                        CollisionFlag.BLOCK_EAST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX, z, level],
                        CollisionFlag.BLOCK_NORTH_ROUTE_BLOCKER,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }
        }
        return false
    }

    private fun routeBlockerFindSize2(
        baseX: Int,
        baseZ: Int,
        level: Int,
        localDestX: Int,
        localDestZ: Int,
        destWidth: Int,
        destLength: Int,
        srcSize: Int,
        locAngle: Int,
        locShape: Int,
        blockAccessFlags: Int,
        collision: CollisionStrategy,
    ): Boolean {
        var x: Int
        var z: Int
        var dirFlag: Int
        val relativeSearchSize = searchMapSize - 2
        while (bufWriterIndex != bufReaderIndex) {
            currLocalX = validLocalX[bufReaderIndex]
            currLocalZ = validLocalZ[bufReaderIndex]
            bufReaderIndex = (bufReaderIndex + 1) and (ringBufferSize - 1)

            if (
                ReachStrategy.reached(
                    flags = flags,
                    level = level,
                    srcX = currLocalX + baseX,
                    srcZ = currLocalZ + baseZ,
                    destX = localDestX + baseX,
                    destZ = localDestZ + baseZ,
                    destWidth = destWidth,
                    destLength = destLength,
                    srcSize = srcSize,
                    locAngle = locAngle,
                    locShape = locShape,
                    blockAccessFlags = blockAccessFlags,
                )
            ) {
                return true
            }

            val nextDistance = distances[currLocalX, currLocalZ] + 1

            /* east to west */
            x = currLocalX - 1
            z = currLocalZ
            dirFlag = DirectionFlag.EAST
            if (
                currLocalX > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + 1, level],
                        BLOCK_NORTH_WEST_ROUTE_BLOCKER,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* west to east */
            x = currLocalX + 1
            z = currLocalZ
            dirFlag = DirectionFlag.WEST
            if (
                currLocalX < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 2, z, level],
                        BLOCK_SOUTH_EAST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 2, currLocalZ + 1, level],
                        CollisionFlag.BLOCK_NORTH_EAST_ROUTE_BLOCKER,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* north to south  */
            x = currLocalX
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH
            if (
                currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 1, z, level],
                        BLOCK_SOUTH_EAST_ROUTE_BLOCKER,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* south to north */
            x = currLocalX
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH
            if (
                currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + 2, level],
                        BLOCK_NORTH_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 1, currLocalZ + 2, level],
                        CollisionFlag.BLOCK_NORTH_EAST_ROUTE_BLOCKER,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* north-east to south-west */
            x = currLocalX - 1
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH_EAST
            if (
                currLocalX > 0 &&
                    currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ, level],
                        BLOCK_NORTH_AND_SOUTH_EAST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX, z, level],
                        BLOCK_NORTH_EAST_AND_WEST_ROUTE_BLOCKER,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* north-west to south-east */
            x = currLocalX + 1
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH_WEST
            if (
                currLocalX < relativeSearchSize &&
                    currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        BLOCK_NORTH_EAST_AND_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 2, z, level],
                        BLOCK_SOUTH_EAST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 2, currLocalZ, level],
                        CollisionFlag.BLOCK_NORTH_AND_SOUTH_WEST_ROUTE_BLOCKER,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* south-east to north-west */
            x = currLocalX - 1
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH_EAST
            if (
                currLocalX > 0 &&
                    currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        BLOCK_NORTH_AND_SOUTH_EAST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + 2, level],
                        BLOCK_NORTH_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX, currLocalZ + 2, level],
                        CollisionFlag.BLOCK_SOUTH_EAST_AND_WEST_ROUTE_BLOCKER,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }

            /* south-west to north-east */
            x = currLocalX + 1
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH_WEST
            if (
                currLocalX < relativeSearchSize &&
                    currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + 2, level],
                        CollisionFlag.BLOCK_SOUTH_EAST_AND_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 2, currLocalZ + 2, level],
                        CollisionFlag.BLOCK_NORTH_EAST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + 2, z, level],
                        CollisionFlag.BLOCK_NORTH_AND_SOUTH_WEST_ROUTE_BLOCKER,
                    )
            ) {
                appendDirection(x, z, dirFlag, nextDistance)
            }
        }
        return false
    }

    private fun routeBlockerFindSizeBig(
        baseX: Int,
        baseZ: Int,
        level: Int,
        localDestX: Int,
        localDestZ: Int,
        destWidth: Int,
        destLength: Int,
        srcSize: Int,
        locAngle: Int,
        locShape: Int,
        blockAccessFlags: Int,
        collision: CollisionStrategy,
    ): Boolean {
        var x: Int
        var z: Int
        var dirFlag: Int
        val relativeSearchSize = searchMapSize - srcSize
        while (bufWriterIndex != bufReaderIndex) {
            currLocalX = validLocalX[bufReaderIndex]
            currLocalZ = validLocalZ[bufReaderIndex]
            bufReaderIndex = (bufReaderIndex + 1) and (ringBufferSize - 1)

            if (
                ReachStrategy.reached(
                    flags = flags,
                    level = level,
                    srcX = currLocalX + baseX,
                    srcZ = currLocalZ + baseZ,
                    destX = localDestX + baseX,
                    destZ = localDestZ + baseZ,
                    destWidth = destWidth,
                    destLength = destLength,
                    srcSize = srcSize,
                    locAngle = locAngle,
                    locShape = locShape,
                    blockAccessFlags = blockAccessFlags,
                )
            ) {
                return true
            }

            val nextDistance = distances[currLocalX, currLocalZ] + 1

            /* east to west */
            x = currLocalX - 1
            z = currLocalZ
            dirFlag = DirectionFlag.EAST
            if (
                currLocalX > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + srcSize - 1, level],
                        BLOCK_NORTH_WEST_ROUTE_BLOCKER,
                    )
            ) {
                val clipFlag = BLOCK_NORTH_AND_SOUTH_EAST_ROUTE_BLOCKER
                val blocked =
                    (1 until srcSize - 1).any {
                        !collision.canMove(flags[baseX, baseZ, x, currLocalZ + it, level], clipFlag)
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* west to east */
            x = currLocalX + 1
            z = currLocalZ
            dirFlag = DirectionFlag.WEST
            if (
                currLocalX < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + srcSize, z, level],
                        BLOCK_SOUTH_EAST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + srcSize, currLocalZ + srcSize - 1, level],
                        CollisionFlag.BLOCK_NORTH_EAST_ROUTE_BLOCKER,
                    )
            ) {
                val clipFlag = CollisionFlag.BLOCK_NORTH_AND_SOUTH_WEST_ROUTE_BLOCKER
                val blocked =
                    (1 until srcSize - 1).any {
                        !collision.canMove(
                            flags[baseX, baseZ, currLocalX + srcSize, currLocalZ + it, level],
                            clipFlag,
                        )
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* north to south  */
            x = currLocalX
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH
            if (
                currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + srcSize - 1, z, level],
                        BLOCK_SOUTH_EAST_ROUTE_BLOCKER,
                    )
            ) {
                val clipFlag = BLOCK_NORTH_EAST_AND_WEST_ROUTE_BLOCKER
                val blocked =
                    (1 until srcSize - 1).any {
                        !collision.canMove(flags[baseX, baseZ, currLocalX + it, z, level], clipFlag)
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* south to north */
            x = currLocalX
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH
            if (
                currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + srcSize, level],
                        BLOCK_NORTH_WEST_ROUTE_BLOCKER,
                    ) &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + srcSize - 1, currLocalZ + srcSize, level],
                        CollisionFlag.BLOCK_NORTH_EAST_ROUTE_BLOCKER,
                    )
            ) {
                val clipFlag = CollisionFlag.BLOCK_SOUTH_EAST_AND_WEST_ROUTE_BLOCKER
                val blocked =
                    (1 until srcSize - 1).any {
                        !collision.canMove(
                            flags[baseX, baseZ, x + it, currLocalZ + srcSize, level],
                            clipFlag,
                        )
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* north-east to south-west */
            x = currLocalX - 1
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH_EAST
            if (
                currLocalX > 0 &&
                    currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, z, level],
                        CollisionFlag.BLOCK_SOUTH_WEST_ROUTE_BLOCKER,
                    )
            ) {
                val clipFlag1 = BLOCK_NORTH_AND_SOUTH_EAST_ROUTE_BLOCKER
                val clipFlag2 = BLOCK_NORTH_EAST_AND_WEST_ROUTE_BLOCKER
                val blocked =
                    (1 until srcSize).any {
                        !collision.canMove(
                            flags[baseX, baseZ, x, currLocalZ + it - 1, level],
                            clipFlag1,
                        ) ||
                            !collision.canMove(
                                flags[baseX, baseZ, currLocalX + it - 1, z, level],
                                clipFlag2,
                            )
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* north-west to south-east */
            x = currLocalX + 1
            z = currLocalZ - 1
            dirFlag = DirectionFlag.NORTH_WEST
            if (
                currLocalX < relativeSearchSize &&
                    currLocalZ > 0 &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + srcSize, z, level],
                        BLOCK_SOUTH_EAST_ROUTE_BLOCKER,
                    )
            ) {
                val clipFlag1 = CollisionFlag.BLOCK_NORTH_AND_SOUTH_WEST_ROUTE_BLOCKER
                val clipFlag2 = BLOCK_NORTH_EAST_AND_WEST_ROUTE_BLOCKER
                val blocked =
                    (1 until srcSize).any {
                        !collision.canMove(
                            flags[baseX, baseZ, currLocalX + srcSize, currLocalZ + it - 1, level],
                            clipFlag1,
                        ) ||
                            !collision.canMove(
                                flags[baseX, baseZ, currLocalX + it, z, level],
                                clipFlag2,
                            )
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* south-east to north-west */
            x = currLocalX - 1
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH_EAST
            if (
                currLocalX > 0 &&
                    currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, x, currLocalZ + srcSize, level],
                        BLOCK_NORTH_WEST_ROUTE_BLOCKER,
                    )
            ) {
                val clipFlag1 = BLOCK_NORTH_AND_SOUTH_EAST_ROUTE_BLOCKER
                val clipFlag2 = CollisionFlag.BLOCK_SOUTH_EAST_AND_WEST_ROUTE_BLOCKER
                val blocked =
                    (1 until srcSize).any {
                        !collision.canMove(
                            flags[baseX, baseZ, x, currLocalZ + it, level],
                            clipFlag1,
                        ) ||
                            !collision.canMove(
                                flags[
                                    baseX, baseZ, currLocalX + it - 1, currLocalZ + srcSize, level],
                                clipFlag2,
                            )
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }

            /* south-west to north-east */
            x = currLocalX + 1
            z = currLocalZ + 1
            dirFlag = DirectionFlag.SOUTH_WEST
            if (
                currLocalX < relativeSearchSize &&
                    currLocalZ < relativeSearchSize &&
                    directions[x, z] == 0 &&
                    collision.canMove(
                        flags[baseX, baseZ, currLocalX + srcSize, currLocalZ + srcSize, level],
                        CollisionFlag.BLOCK_NORTH_EAST_ROUTE_BLOCKER,
                    )
            ) {
                val clipFlag1 = CollisionFlag.BLOCK_SOUTH_EAST_AND_WEST_ROUTE_BLOCKER
                val clipFlag2 = CollisionFlag.BLOCK_NORTH_AND_SOUTH_WEST_ROUTE_BLOCKER
                val blocked =
                    (1 until srcSize).any {
                        !collision.canMove(
                            flags[baseX, baseZ, currLocalX + it, currLocalZ + srcSize, level],
                            clipFlag1,
                        ) ||
                            !collision.canMove(
                                flags[baseX, baseZ, currLocalX + srcSize, currLocalZ + it, level],
                                clipFlag2,
                            )
                    }
                if (!blocked) {
                    appendDirection(x, z, dirFlag, nextDistance)
                }
            }
        }
        return false
    }

    private fun findClosestApproachPoint(
        localDestX: Int,
        localDestZ: Int,
        width: Int,
        length: Int,
    ): Boolean {
        var lowestCost = MAX_ALTERNATIVE_ROUTE_LOWEST_COST
        var maxAlternativePath = MAX_ALTERNATIVE_ROUTE_SEEK_RANGE
        val alternativeRouteRange = MAX_ALTERNATIVE_ROUTE_DISTANCE_FROM_DESTINATION
        val radiusX = localDestX - alternativeRouteRange..localDestX + alternativeRouteRange
        val radiusZ = localDestZ - alternativeRouteRange..localDestZ + alternativeRouteRange
        for (x in radiusX) {
            for (z in radiusZ) {
                if (
                    x !in 0 until searchMapSize ||
                        z !in 0 until searchMapSize ||
                        distances[x, z] >= MAX_ALTERNATIVE_ROUTE_SEEK_RANGE
                ) {
                    continue
                }

                val dx =
                    if (x < localDestX) {
                        localDestX - x
                    } else if (x > localDestX + width - 1) {
                        x - (width + localDestX - 1)
                    } else {
                        0
                    }

                val dy =
                    if (z < localDestZ) {
                        localDestZ - z
                    } else if (z > localDestZ + length - 1) {
                        z - (localDestZ + length - 1)
                    } else {
                        0
                    }
                val cost = dx * dx + dy * dy
                if (
                    cost < lowestCost ||
                        (cost == lowestCost && maxAlternativePath > distances[x, z])
                ) {
                    currLocalX = x
                    currLocalZ = z
                    lowestCost = cost
                    maxAlternativePath = distances[x, z]
                }
            }
        }
        return lowestCost != MAX_ALTERNATIVE_ROUTE_LOWEST_COST
    }

    private fun appendDirection(x: Int, z: Int, direction: Int, distance: Int) {
        val index = (x * searchMapSize) + z
        directions[index] = direction
        distances[index] = distance
        validLocalX[bufWriterIndex] = x
        validLocalZ[bufWriterIndex] = z
        bufWriterIndex = (bufWriterIndex + 1) and (ringBufferSize - 1)
    }

    private fun reset() {
        Arrays.fill(directions, 0)
        Arrays.fill(distances, DEFAULT_DISTANCE_VALUE)
        bufReaderIndex = 0
        bufWriterIndex = 0
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline operator fun IntArray.get(x: Int, z: Int): Int {
        val index = (x * searchMapSize) + z
        return this[index]
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline operator fun CollisionFlagMap.get(
        baseX: Int,
        baseZ: Int,
        localX: Int,
        localZ: Int,
        level: Int,
    ): Int {
        val x = baseX + localX
        val z = baseZ + localZ
        return this[x, z, level]
    }

    public companion object {
        /**
         * Calculates coordinates for [sourceX]/[sourceZ] to move to interact with
         * [targetX]/[targetZ] We first determine the cardinal direction of the source relative to
         * the target by comparing if the source lies to the left or right of diagonal \ and
         * anti-diagonal / lines.
         *
         * ```
         * \ <= North <= /
         *  +------------+  >
         *  |            |  East
         *  +------------+  <
         * / <= South <= \
         * ```
         *
         * We then further bisect the area into three section relative to the south-west tile
         * (zero):
         * 1. Greater than zero: follow their diagonal until the target side is reached (clamped at
         *    the furthest most tile)
         * 2. Less than zero: zero minus the size of the source
         * 3. Equal to zero: move directly towards zero / the south-west coordinate
         *
         * ```
         * <  \ 0 /   <   /
         *     +---------+
         *     |         |
         *     +---------+
         * ```
         *
         * This method is equivalent to returning the last coordinate in a sequence of steps towards
         * south-west when moving ordinal then cardinally until entity side comes into contact with
         * another.
         */
        public fun naiveDestination(
            sourceX: Int,
            sourceZ: Int,
            sourceWidth: Int,
            sourceLength: Int,
            targetX: Int,
            targetZ: Int,
            targetWidth: Int,
            targetLength: Int,
            targetAngle: Int = 0,
        ): RouteCoordinates {
            val diagonal = (sourceX - targetX) + (sourceZ - targetZ)
            val anti = (sourceX - targetX) - (sourceZ - targetZ)
            val rotatedWidth = rotate(targetAngle, targetWidth, targetLength)
            val rotatedLength = rotate(targetAngle, targetLength, targetWidth)
            val southWestClockwise = anti < 0
            val northWestClockwise = diagonal >= (rotatedLength - 1) - (sourceWidth - 1)
            val northEastClockwise = anti > sourceWidth - sourceLength
            val southEastClockwise = diagonal <= (rotatedWidth - 1) - (sourceLength - 1)

            val target = RouteCoordinates(targetX, targetZ)
            if (southWestClockwise && !northWestClockwise) {
                val offZ =
                    when { // West
                        diagonal >= -sourceWidth ->
                            (diagonal + sourceWidth).coerceAtMost(rotatedLength - 1)
                        anti > -sourceWidth -> -(sourceWidth + anti)
                        else -> 0
                    }
                return target.translate(-sourceWidth, offZ)
            } else if (northWestClockwise && !northEastClockwise) {
                val offX =
                    when { // North
                        anti >= -rotatedLength ->
                            (anti + rotatedLength).coerceAtMost(rotatedWidth - 1)
                        diagonal < rotatedLength ->
                            (diagonal - rotatedLength).coerceAtLeast(-(sourceWidth - 1))
                        else -> 0
                    }
                return target.translate(offX, rotatedLength)
            } else if (northEastClockwise && !southEastClockwise) {
                val offZ =
                    when { // East
                        anti <= rotatedWidth -> rotatedLength - anti
                        diagonal < rotatedWidth ->
                            (diagonal - rotatedWidth).coerceAtLeast(-(sourceLength - 1))
                        else -> 0
                    }
                return target.translate(rotatedWidth, offZ)
            } else {
                check(southEastClockwise && !southWestClockwise)
                val offX =
                    when { // South
                        diagonal > -sourceLength ->
                            (diagonal + sourceLength).coerceAtMost(rotatedWidth - 1)
                        anti < sourceLength ->
                            (anti - sourceLength).coerceAtLeast(-(sourceLength - 1))
                        else -> 0
                    }
                return target.translate(offX, -sourceLength)
            }
        }
    }
}
