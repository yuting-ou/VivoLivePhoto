package com.vliveconvert.app

import com.vliveconvert.app.core.XmpTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * XMP GPS 属性 → 十进制经纬度（GCamera 约定形态）。
 *
 * 为什么重要：部分 vivo 机型把位置写进 XMP 而非 EXIF——导出坐标链的第二来源。
 * 解析错/漏，这批产物就出生无坐标，相册不显示位置（批量必丢位置问题的候选根因之一）。
 */
class XmpGpsCoordsTest {

    @Test
    fun parsesDegMinHemisphereForm() {
        val xmp = """<rdf:Description exif:GPSLatitude="22,50.2088N" exif:GPSLongitude="108,17.84E"/>"""
        val (lat, lon) = XmpTemplate.gpsCoordsFromXmp(xmp)!!
        assertEquals(22.0 + 50.2088 / 60.0, lat, 1e-9)
        assertEquals(108.0 + 17.84 / 60.0, lon, 1e-9)
    }

    @Test
    fun parsesDegMinSecForm() {
        val xmp = """<rdf:Description exif:GPSLatitude="22,50,30.5N" exif:GPSLongitude="108,17,50.4E"/>"""
        val (lat, lon) = XmpTemplate.gpsCoordsFromXmp(xmp)!!
        assertEquals(22.0 + 50.0 / 60.0 + 30.5 / 3600.0, lat, 1e-9)
        assertEquals(108.0 + 17.0 / 60.0 + 50.4 / 3600.0, lon, 1e-9)
    }

    @Test
    fun refAttributeSuppliesHemisphere() {
        val xmp = """<rdf:Description exif:GPSLatitude="22.8389" exif:GPSLatitudeRef="N" """ +
            """exif:GPSLongitude="108.178" exif:GPSLongitudeRef="E"/>"""
        val (lat, lon) = XmpTemplate.gpsCoordsFromXmp(xmp)!!
        assertEquals(22.8389, lat, 1e-9)
        assertEquals(108.178, lon, 1e-9)
    }

    @Test
    fun southAndWestAreNegative() {
        val xmp = """<rdf:Description exif:GPSLatitude="33,52.0S" exif:GPSLongitude="18,25.0W"/>"""
        val (lat, lon) = XmpTemplate.gpsCoordsFromXmp(xmp)!!
        assertEquals(-(33.0 + 52.0 / 60.0), lat, 1e-9)
        assertEquals(-(18.0 + 25.0 / 60.0), lon, 1e-9)
    }

    @Test
    fun elementFormIsAlsoAccepted() {
        val xmp = """<rdf:Description><exif:GPSLatitude>22,50.2088N</exif:GPSLatitude>""" +
            """<exif:GPSLongitude>108,17.84E</exif:GPSLongitude></rdf:Description>"""
        val (lat, lon) = XmpTemplate.gpsCoordsFromXmp(xmp)!!
        assertEquals(22.0 + 50.2088 / 60.0, lat, 1e-9)
        assertEquals(108.0 + 17.84 / 60.0, lon, 1e-9)
    }

    @Test
    fun noGpsOrNullReturnsNull() {
        assertNull(XmpTemplate.gpsCoordsFromXmp(null))
        assertNull(XmpTemplate.gpsCoordsFromXmp("<rdf:Description GCamera:MotionPhoto=\"1\"/>"))
    }

    @Test
    fun zeroZeroIsNotALocation() {
        val xmp = """<rdf:Description exif:GPSLatitude="0,0.0N" exif:GPSLongitude="0,0.0E"/>"""
        assertNull(XmpTemplate.gpsCoordsFromXmp(xmp))
    }
}
