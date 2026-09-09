package com.example.opennanoor.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import org.xmlpull.v1.XmlPullParser

/** An icon pack installed on the device. */
data class IconPackInfo(
    val packageName: String,
    val label: String
)

/**
 * Loads and applies a standard `appfilter.xml` icon pack - the de facto format
 * shared by Nova, Lawnchair, ADW and effectively every pack on the Play Store.
 *
 * Two layers of theming:
 *  1. Explicit mappings: the pack ships bespoke art for a given component.
 *  2. Fallback compositing: for apps the pack never drew, the original icon is
 *     scaled onto the pack's `iconback` and clipped by its `iconmask`, so
 *     unthemed apps still match the set rather than standing out.
 */
class IconPack private constructor(
    private val packageName: String,
    private val resources: Resources,
    private val mappings: Map<String, String>,
    private val backNames: List<String>,
    private val maskName: String?,
    private val uponName: String?,
    private val scale: Float
) {

    private val cache = mutableMapOf<String, Drawable?>()

    /**
     * The pack's art for [component], or a composited approximation when the
     * pack doesn't cover it. Null only if we can't produce anything at all.
     */
    fun iconFor(component: ComponentName, fallback: Drawable, sizePx: Int): Drawable? {
        val key = component.flattenToString()
        return cache.getOrPut(key) {
            themedIcon(key) ?: compositeIcon(key, fallback, sizePx)
        }
    }

    private fun themedIcon(componentKey: String): Drawable? {
        val drawableName = mappings[componentKey] ?: return null
        return drawableByName(drawableName)
    }

    private fun drawableByName(name: String): Drawable? {
        val id = resources.getIdentifier(name, "drawable", packageName)
        if (id == 0) return null
        return runCatching { resources.getDrawable(id, null) }.getOrNull()
    }

    /**
     * Builds an icon in the pack's style out of the app's own icon. Packs that
     * ship no back or mask get the original icon back untouched.
     */
    private fun compositeIcon(componentKey: String, fallback: Drawable, sizePx: Int): Drawable? {
        if (backNames.isEmpty() && maskName == null) return null
        if (sizePx <= 0) return null

        val result = createBitmap(sizePx, sizePx)
        val canvas = Canvas(result)
        val bounds = Rect(0, 0, sizePx, sizePx)

        // Deterministic per-app choice so an app keeps the same back every launch.
        backNames.takeIf { it.isNotEmpty() }
            ?.let { it[Math.floorMod(componentKey.hashCode(), it.size)] }
            ?.let { drawableByName(it) }
            ?.apply { setBounds(bounds); draw(canvas) }

        // The app's own icon, inset by the pack's declared scale.
        val inset = ((1f - scale) * sizePx / 2f).toInt()
        val scaled = fallback.toBitmap(sizePx, sizePx)
        val maskedApp = createBitmap(sizePx, sizePx)
        Canvas(maskedApp).apply {
            drawBitmap(
                scaled,
                null,
                Rect(inset, inset, sizePx - inset, sizePx - inset),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            maskName?.let { drawableByName(it) }?.let { mask ->
                mask.setBounds(bounds)
                val maskBitmap = mask.toBitmap(sizePx, sizePx)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                }
                drawBitmap(maskBitmap, 0f, 0f, paint)
            }
        }
        canvas.drawBitmap(maskedApp, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))

        // Optional gloss layer painted on top.
        uponName?.let { drawableByName(it) }?.apply { setBounds(bounds); draw(canvas) }

        return android.graphics.drawable.BitmapDrawable(resources, result)
    }

    companion object {
        /** Intents that icon packs advertise themselves with. */
        private val PACK_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.gau.go.launcherex.theme",
            "com.novalauncher.THEME",
            "ch.deletescape.lawnchair.ICONPACK"
        )

        /** Every icon pack currently installed. */
        fun installedPacks(context: Context): List<IconPackInfo> {
            val pm = context.packageManager
            val found = linkedMapOf<String, IconPackInfo>()
            PACK_ACTIONS.forEach { action ->
                val intent = Intent(action)
                pm.queryIntentActivities(intent, PackageManager.GET_META_DATA).forEach { info ->
                    val pkg = info.activityInfo.packageName
                    found.getOrPut(pkg) {
                        IconPackInfo(pkg, info.loadLabel(pm).toString())
                    }
                }
            }
            return found.values.sortedBy { it.label.lowercase() }
        }

        /** Parses [packageName]'s appfilter, or null if it isn't readable. */
        fun load(context: Context, packageName: String): IconPack? {
            val resources = runCatching {
                context.packageManager.getResourcesForApplication(packageName)
            }.getOrNull() ?: return null

            val parser = openAppFilter(resources, packageName) ?: return null

            val mappings = mutableMapOf<String, String>()
            val backs = mutableListOf<String>()
            var mask: String? = null
            var upon: String? = null
            var scale = 1f

            runCatching {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    when (parser.name) {
                        "item" -> {
                            val component = parser.getAttributeValue(null, "component")
                            val drawable = parser.getAttributeValue(null, "drawable")
                            if (component != null && drawable != null) {
                                normaliseComponent(component)?.let { mappings[it] = drawable }
                            }
                        }
                        "iconback" -> collectImgs(parser).let(backs::addAll)
                        "iconmask" -> mask = collectImgs(parser).firstOrNull() ?: mask
                        "iconupon" -> upon = collectImgs(parser).firstOrNull() ?: upon
                        "scale" -> parser.getAttributeValue(null, "factor")
                            ?.toFloatOrNull()?.let { scale = it.coerceIn(0.1f, 1f) }
                    }
                }
            }

            if (mappings.isEmpty() && backs.isEmpty() && mask == null) return null

            return IconPack(packageName, resources, mappings, backs, mask, upon, scale)
        }

        private fun openAppFilter(resources: Resources, packageName: String): XmlPullParser? {
            val xmlId = resources.getIdentifier("appfilter", "xml", packageName)
            if (xmlId != 0) {
                return runCatching { resources.getXml(xmlId) }.getOrNull()
            }
            // Some packs ship it as a raw asset instead.
            return runCatching {
                val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
                factory.newPullParser().apply {
                    setInput(resources.assets.open("appfilter.xml"), null)
                }
            }.getOrNull()
        }

        /** `<iconback img="a" img1="b" .../>` - collect every img attribute. */
        private fun collectImgs(parser: XmlPullParser): List<String> =
            (0 until parser.attributeCount)
                .filter { parser.getAttributeName(it).startsWith("img") }
                .mapNotNull { parser.getAttributeValue(it) }

        /**
         * Packs write components as `ComponentInfo{pkg/cls}`. Reduce that to the
         * same flattened form [ComponentName.flattenToString] produces.
         */
        private fun normaliseComponent(raw: String): String? {
            val inner = raw.substringAfter("ComponentInfo{", raw).substringBefore("}")
            if (!inner.contains('/')) return null
            return inner
        }
    }
}
