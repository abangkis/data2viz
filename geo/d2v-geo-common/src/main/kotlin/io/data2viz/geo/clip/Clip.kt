package io.data2viz.geo.clip

import io.data2viz.geo.polygonContains
import io.data2viz.geo.projection.Stream
import io.data2viz.math.EPSILON
import io.data2viz.math.HALFPI

/**
 * Takes a line and cuts into visible segments. Values for clean:
 *  0 - there were intersections or the line was empty;
 *  1 - no intersections;
 *  2 - there were intersections, and the first and last segments should be rejoined.
 */
interface ClipStream : Stream {
    var clean: Int
}

interface Clippable {
    fun pointVisible(x: Double, y: Double): Boolean
    fun clipLine(stream: Stream): ClipStream
    fun interpolate(from: DoubleArray?, to: DoubleArray?, direction: Int, stream: Stream)
}

interface ClippableHasStart : Clippable {
    val start: DoubleArray
}


interface CurrentPointApi {

    fun invoke(x: Double, y: Double, z: Double)
}



class Clip(val clip: ClippableHasStart, val sink: Stream) : Stream {

    class DefaultCurrentPoint(val clip:Clip): CurrentPointApi {
        override fun invoke(x: Double, y: Double, z: Double) {
            if (clip.clip.pointVisible(x, y)) clip.sink.point(x, y, z)
        }

    }

    class LineCurrentPoint(val clip:Clip): CurrentPointApi {
        override fun invoke(x: Double, y: Double, z: Double) {
            clip.line.point(x, y, z)
        }


    }
    class PointRingCurrentPoint(val clip:Clip): CurrentPointApi {
        override fun invoke(x: Double, y: Double, z: Double) {
            clip.ring!!.add(doubleArrayOf(x, y))
            clip.ringSink.point(x, y, z)
        }

    }

    private val line = clip.clipLine(sink)

    private val ringBuffer = ClipBuffer()
    private val ringSink = clip.clipLine(ringBuffer)

    private var polygonStarted = false

    private val polygon: MutableList<List<DoubleArray>> = mutableListOf()
    private val segments: MutableList<List<List<DoubleArray>>> = mutableListOf()
    private var ring: MutableList<DoubleArray>? = null

//    private var currentPoint: (Double, Double, Double) -> Unit = ::defaultPoint
    private var currentPoint: CurrentPointApi = DefaultCurrentPoint(this)
    private var currentLineStart: () -> Unit = ::defaultLineStart
    private var currentLineEnd: () -> Unit = ::defaultLineEnd

    private val compareIntersection = Comparator<Intersection> { i1, i2 ->
        val a = i1.point
        val b = i2.point
        val ca = if (a[0] < 0) a[1] - HALFPI - EPSILON else HALFPI - a[1]
        val cb = if (b[0] < 0) b[1] - HALFPI - EPSILON else HALFPI - b[1]
        ca.compareTo(cb)
    }

    override fun point(x: Double, y: Double, z: Double) {
//        currentPoint(x, y, z)
        currentPoint.invoke(x, y, z)
    }

    override fun lineStart() {
        currentLineStart()
    }

    override fun lineEnd() {
        currentLineEnd()
    }

    override fun polygonStart() {
//        currentPoint = ::pointRing
        currentPoint = PointRingCurrentPoint(this)
        currentLineStart = ::ringStart
        currentLineEnd = ::ringEnd
    }

    val interpolateFunction = object :InterpolateFunction {
        override fun invoke(from: DoubleArray, to: DoubleArray, direction: Int, stream: Stream) {
            clip.interpolate(from, to, direction, stream)
        }

    }

    override fun polygonEnd() {
//        currentPoint = ::defaultPoint
        currentPoint = DefaultCurrentPoint(this)
        currentLineStart = ::defaultLineStart
        currentLineEnd = ::defaultLineEnd

        val startInside = polygonContains(polygon, clip.start)

        if (segments.isNotEmpty()) {
            if (!polygonStarted) {
                sink.polygonStart()
                polygonStarted = true
            }

//            rejoin(segments.flatten(), compareIntersection, startInside, clip::interpolate, sink)
            rejoin(segments.flatten(), compareIntersection, startInside, interpolateFunction, sink)
        } else if (startInside) {
            if (!polygonStarted) {
                sink.polygonStart()
                polygonStarted = true
            }
            sink.lineStart()
            clip.interpolate(null, null, 1, sink)
            sink.lineEnd()
        }

        if (polygonStarted) {
            sink.polygonEnd()
            polygonStarted = false
        }

        segments.clear()
        polygon.clear()
    }

    override fun sphere() {
        sink.polygonStart()
        sink.lineStart()
        clip.interpolate(null, null, 1, sink)
        sink.lineEnd()
        sink.polygonEnd()
    }

    private fun defaultPoint(x: Double, y: Double, z: Double) {
        if (clip.pointVisible(x, y)) sink.point(x, y, z)
    }

    private fun pointLine(x: Double, y: Double, z: Double) = line.point(x, y, z)
    private fun pointRing(x: Double, y: Double, z: Double) {
        ring!!.add(doubleArrayOf(x, y))
        ringSink.point(x, y, z)
    }

    private fun defaultLineStart() {
//        currentPoint = ::pointLine
        currentPoint = LineCurrentPoint(this)
        line.lineStart()
    }

    private fun defaultLineEnd() {
//        currentPoint = ::defaultPoint
        currentPoint = DefaultCurrentPoint(this)
        line.lineEnd()
    }

    private fun ringStart() {
        ringSink.lineStart()
        ring = mutableListOf()
    }


    companion object {
        const val zeroZ = 0.0
    }
    private fun ringEnd() {
        requireNotNull(ring, { "Error on Clip.ringEnd, ring can't be null." })

        pointRing(ring!![0][0], ring!![0][1], zeroZ)
        ringSink.lineEnd()

        val clean = ringSink.clean
        val ringSegments: MutableList<List<DoubleArray>> = ringBuffer.result().toMutableList()

        ring!!.removeAt(ring!!.lastIndex)
        polygon.add(ring!!)
        this.ring = null

        if (ringSegments.isEmpty()) return

        // No intersections
        if ((clean and 1) != 0) {
            val segment = ringSegments[0]
            val m = segment.lastIndex
            if (m > 0) {
                if (!polygonStarted) {
                    sink.polygonStart()
                    polygonStarted = true
                }
                sink.lineStart()
                (0 until m).forEach {
                    val currentSegmentPiece = segment[it]
                    val x = currentSegmentPiece[0]
                    val y = currentSegmentPiece[1]
                    sink.point(x, y, zeroZ)
                }
                sink.lineEnd()
            }
            return
        }

        // Rejoin connected segments
        // TODO reuse ringBuffer.rejoin()?
        if (ringSegments.size > 1 && (clean and 2) != 0) {
            val concat = ringSegments.removeAt(ringSegments.lastIndex).toMutableList()
            concat.addAll(ringSegments.removeAt(0))
            ringSegments.add(concat)
        }

        segments.add(ringSegments.filter { it.size > 1 })
    }
}