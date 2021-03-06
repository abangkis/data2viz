/*
 * Copyright (c) 2018-2019. data2viz sàrl.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

@file:Suppress("DEPRECATION")

package io.data2viz.color

import io.data2viz.geom.Point
import io.data2viz.math.Percent
import io.data2viz.math.pct

// TODO : move to "core.geom" ?
// TODO : remove access to cx, cy, leave only access to center
interface HasCenter {
    var cx: Double
    var cy: Double

    var center:Point
        get() = Point(cx,cy)
        set(value) {
            cx = value.x
            cy = value.y
        }
}

data class RadialGradientFirstColorBuilder
internal constructor(val center: Point, val radius: Double) {
    fun withColor(startColor: Color, percent: Percent = 0.pct): RadialGradientSecondColorBuilder =
        RadialGradientSecondColorBuilder(this, ColorStop(percent, startColor))
}

data class RadialGradientSecondColorBuilder
internal constructor(val builder: RadialGradientFirstColorBuilder, val firstColor: ColorStop) {
    fun andColor(color: Color, percent: Percent = 100.pct): RadialGradient = RadialGradient()
        .apply {
            cx = builder.center.x
            cy = builder.center.y
            radius = builder.radius
            andColor(firstColor.color, firstColor.percent)
            andColor(color, percent)
        }
}

class RadialGradient

@Deprecated("Deprecated", ReplaceWith("Colors.Gradient.radial()", "io.data2viz.colors.Colors"))
constructor(): Gradient, HasCenter {

    override var cx:Double = .0
    override var cy:Double = .0
    var radius:Double = .0

    private val colors = mutableListOf<ColorStop>()
    override val colorStops: List<ColorStop>
        get() = colors.toList()

    fun andColor(color: Color, percent: Percent): RadialGradient {
        colors.add(ColorStop(percent.coerceToDefault(), color))
        return this
    }
}