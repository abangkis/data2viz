package io.data2viz.geo.projection

import io.data2viz.geo.ModifiedStream
import io.data2viz.math.toDegrees
import io.data2viz.math.toRadians
import kotlin.math.PI
import kotlin.math.sqrt

const val HALF_PI = PI / 2

/*data class GeoPoint(
    val latitude: Double,           // equivalent to PHI (φ)
    val longitude: Double,           // equivalent to lambda (λ)
    val elevation: Double = .0
)

data class Point(
    val x: Double,
    val y: Double
)*/

data class Extent(
    var x0: Double,
    var y0: Double,
    var x1: Double,
    var y1: Double
)

interface Stream {
    fun point(x: Double, y: Double, z: Double) {}
    fun lineStart() {}
    fun lineEnd() {}
    fun polygonStart() {}
    fun polygonEnd() {}
    fun sphere() {}
}

interface Projectable {
    fun project(lambda: Double, phi: Double): DoubleArray
}

interface Invertable {
    fun invert(x: Double, y: Double): DoubleArray
}

interface ProjectableInvertable : Projectable, Invertable

interface Projection : ProjectableInvertable {
    var scale: Double
    var translate: DoubleArray
    var center: DoubleArray
    var precision: Double
    var rotate: DoubleArray

    var preClip: (Stream) -> Stream
    var postClip: (Stream) -> Stream
    var clipAngle: Double
    var clipExtent: Extent?

    //fun rotate(angle: Double)
    fun stream(stream: Stream): Stream

    // TODO : fits
}

fun compose(a: Projectable, b: Projectable): Projectable {
    if (a is Invertable && b is Invertable) {
        return object : ProjectableInvertable {
            override fun project(lambda: Double, phi: Double): DoubleArray {
                val p = a.project(lambda, phi)
                return b.project(p[0], p[1])
            }

            override fun invert(x: Double, y: Double): DoubleArray {
                val p = b.invert(x, y)
                return a.invert(p[0], p[1])
            }
        }
    } else {
        return object : Projectable {
            override fun project(lambda: Double, phi: Double): DoubleArray {
                val p = a.project(lambda, phi)
                return b.project(p[0], p[1])
            }
        }
    }
}

internal fun resampleNone(projection: Projectable): (Stream) -> Stream {
    return { stream: Stream ->
        object : ModifiedStream(stream) {
            override fun point(x: Double, y: Double, z: Double) {
                val p = projection.project(x, y)
                stream.point(p[0], p[1], 0.0)
            }
        }
    }
}

class TransformRadians(stream: Stream) : ModifiedStream(stream) {
    override fun point(x: Double, y: Double, z: Double) =
        stream.point(x.toRadians(), y.toRadians(), z.toRadians())
}

fun projection(projection: Projectable, init: MutableProjection.() -> Unit) = MutableProjection(projection).apply(init)

// TODO clipping
open class MutableProjection(val projection: Projectable) : Projection {

    protected var cache: Stream? = null
    protected var cacheStream: Stream? = null

    // TODO Change
    protected fun getCachedStream(stream: Stream): Stream? =
        if (cache != null && cacheStream == stream) cache else null

    // TODO Change
    protected fun cache(stream1: Stream, stream2: Stream) {
        cache = stream2
        cacheStream = stream1
    }

    val noClip: (Stream) -> Stream = { it }
    override var preClip: (Stream) -> Stream
        get() = noClip
        set(value) {}

    override var postClip: (Stream) -> Stream
        get() = noClip
        set(value) {}

    override var clipAngle: Double
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
        set(value) {}

    override var clipExtent: Extent? = null
        set(value) {
            field = value
            //if (value != null) reclip()
        }

    /*constructor(projector: Projector) : this(object : ProjectorFactory {
        override fun create(): Projector {
            return projector
        }
    })

    protected var project: Projector = factory.create()*/

    // Scale
    private var k = 150.0

    override var scale: Double
        get() = k
        set(value) {
            k = value
            recenter()
        }

    // Translate
    private var x = 480.0
    private var y = 250.0
    override var translate: DoubleArray
        get() = doubleArrayOf(x, y)
        set(value) {
            x = value[0]
            y = value[1]
            recenter()
        }

    // Center
    private var dx = 0.0
    private var dy = 0.0
    private var lambda = 0.0
    private var phi = 0.0
    override var center
        get() = doubleArrayOf(lambda.toDegrees(), phi.toDegrees())
        set(value) {
            lambda = (value[0] % 360).toRadians()
            phi = (value[1] % 360).toRadians()
            recenter()
        }

    // Rotate
    private var deltaLambda = 0.0
    private var deltaPhi = 0.0
    private var deltaGamma = 0.0
    private lateinit var rotator: Projectable

    override var rotate: DoubleArray
        get() = doubleArrayOf(deltaLambda.toDegrees(), deltaPhi.toDegrees(), deltaGamma.toDegrees())
        set(value) {
            deltaLambda = (value[0] % 360).toRadians()
            deltaPhi = (value[1] % 360).toRadians()
            deltaGamma = if (value.size > 2) (value[2] % 360).toRadians() else 0.0
            recenter()
        }

    private lateinit var projectRotate: Projectable

    private val projectTransform:Projectable = object : Projectable {
        override fun project(lambda: Double, phi: Double): DoubleArray {
            val p = projection.project(lambda, phi)
            return doubleArrayOf(p[0] * k + dx, dy - p[1] * k)
        }
    }

    // Precision
    private var delta2 = 0.5
    private var projectResample: (Stream) -> Stream = resampleNone(projectTransform)
    //private var projectResample = resample(projectTransform, delta2)
    override var precision: Double
        get() = sqrt(delta2)
        set(value) {
            delta2 = value * value
            //projectResample = resample(projectTransform, delta2)
            //reset()
        }

    private val transformRadians: (stream: Stream) -> ModifiedStream = { stream: Stream ->
        object : ModifiedStream(stream) {
            override fun point(x: Double, y: Double, z: Double) =
                stream.point(x.toRadians(), y.toRadians(), z.toRadians())
        }
    }

    private fun transformRotate(rotate: Projectable): (stream: Stream) -> ModifiedStream = { stream: Stream ->
        object : ModifiedStream(stream) {
            override fun point(x: Double, y: Double, z: Double) {
                val r = rotate.project(x, y)
                stream.point(r[0], r[1], 0.0)
            }
        }
    }

    override fun project(lambda: Double, phi: Double): DoubleArray {
        val p = projectRotate.project(lambda.toRadians(), phi.toRadians())
        return doubleArrayOf(p[0] * k + dx, dy - p[1] * k)
    }

    override fun invert(x: Double, y: Double): DoubleArray {
        require(projectRotate is Invertable, { "This projection is not invertable." })

        val p = (projectRotate as Invertable).invert((x - dx) / k, (dy - y) / k)
        return doubleArrayOf(p[0].toDegrees(), p[1].toDegrees())
    }

    override fun stream(stream: Stream): Stream {
        var cachedStream = getCachedStream(stream)
        if (cachedStream == null) {
            cachedStream = transformRadians(transformRotate(rotator)(preClip(projectResample(postClip(stream)))))
            cache(cachedStream, cachedStream)
        }
        return cachedStream
    }

    fun recenter(): Projection {
        rotator = rotateRadians(deltaLambda, deltaPhi, deltaGamma)
        projectRotate = compose(rotator, projection)
        val center = projection.project(lambda, phi)
        dx = x - (center[0] * k)
        dy = y + (center[1] * k)
        return reset()
    }

    fun reset(): Projection {
        cache = null
        cacheStream = null
        return this
    }
}
