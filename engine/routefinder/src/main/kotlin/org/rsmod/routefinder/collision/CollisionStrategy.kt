package org.rsmod.routefinder.collision

import org.rsmod.routefinder.flag.CollisionFlag

public interface CollisionStrategy {
    public fun canMove(tileFlag: Int, blockFlag: Int): Boolean

    public object Normal : CollisionStrategy {
        override fun canMove(tileFlag: Int, blockFlag: Int): Boolean {
            return (tileFlag and blockFlag) == 0
        }
    }

    public object Blocked : CollisionStrategy {
        override fun canMove(tileFlag: Int, blockFlag: Int): Boolean {
            val flag = blockFlag and CollisionFlag.BLOCK_WALK.inv()
            return (tileFlag and flag) == 0 && (tileFlag and CollisionFlag.BLOCK_WALK) != 0
        }
    }

    public object Indoors : CollisionStrategy {
        override fun canMove(tileFlag: Int, blockFlag: Int): Boolean {
            return (tileFlag and blockFlag) == 0 && (tileFlag and CollisionFlag.ROOF) != 0
        }
    }

    public object Outdoors : CollisionStrategy {
        override fun canMove(tileFlag: Int, blockFlag: Int): Boolean {
            return (tileFlag and (blockFlag or CollisionFlag.ROOF)) == 0
        }
    }

    public object LineOfSight : CollisionStrategy {
        private const val BLOCK_MOVEMENT =
            CollisionFlag.WALL_NORTH_WEST or
                CollisionFlag.WALL_NORTH or
                CollisionFlag.WALL_NORTH_EAST or
                CollisionFlag.WALL_EAST or
                CollisionFlag.WALL_SOUTH_EAST or
                CollisionFlag.WALL_SOUTH or
                CollisionFlag.WALL_SOUTH_WEST or
                CollisionFlag.WALL_WEST or
                CollisionFlag.LOC

        private const val BLOCK_ROUTE =
            CollisionFlag.WALL_NORTH_WEST_ROUTE_BLOCKER or
                CollisionFlag.WALL_NORTH_ROUTE_BLOCKER or
                CollisionFlag.WALL_NORTH_EAST_ROUTE_BLOCKER or
                CollisionFlag.WALL_EAST_ROUTE_BLOCKER or
                CollisionFlag.WALL_SOUTH_EAST_ROUTE_BLOCKER or
                CollisionFlag.WALL_SOUTH_ROUTE_BLOCKER or
                CollisionFlag.WALL_SOUTH_WEST_ROUTE_BLOCKER or
                CollisionFlag.WALL_WEST_ROUTE_BLOCKER or
                CollisionFlag.LOC_ROUTE_BLOCKER

        override fun canMove(tileFlag: Int, blockFlag: Int): Boolean {
            val movementFlags = (blockFlag and BLOCK_MOVEMENT) shl 9
            val routeFlags = (blockFlag and BLOCK_ROUTE) shr 13
            val finalBlockFlag = movementFlags or routeFlags
            return (tileFlag and finalBlockFlag) == 0
        }
    }
}
