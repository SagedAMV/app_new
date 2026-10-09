package com.unihub.app.feature.galaxy

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

/**
 * اختبارات وحدة محلية للدوال الهندسية المجردة في [GalaxyGeometry].
 * هذه الدوال نقية (Pure Functions) ولا تعتمد على Android، لذا يمكن اختبارها على JVM مباشرة.
 * 
 * التركيز هنا على الدوال الجديدة التي تدعم دوران المدارات (Orbit Rotation).
 */
class GalaxyGeometryTest {

    /**
     * اختبار 1: عند زاوية دوران = 0، يجب أن تكون الإحداثيات المطابقة للدوال القديمة (قبل الحذف).
     * هذا يضمن أن الدوران لا يكسر التموضع الأساسي عندما لا يكون هناك حركة.
     */
    @Test
    fun orbitRotation_zeroAngle_matchesOriginalLogic() {
        val index = 0
        val childCount = 6
        val orbitAngle = 0.0
        
        val dx = GalaxyGeometry.childCenterDxDpWithOrbit(index, childCount, orbitAngle)
        val dy = GalaxyGeometry.childCenterDyDpWithOrbit(index, childCount, orbitAngle)
        
        // الحساب المتوقع: نصف قطر الحلقة * cos(الزاوية الأصلية)
        val radius = GalaxyGeometry.childRingRadiusDp(index)
        // الزاوية الأصلية للحلقة الأولى (index 0) هي -PI/2 (أعلى الدائرة)
        val originalAngle = -PI / 2.0
        
        val expectedDx = radius * kotlin.math.cos(originalAngle).toFloat()
        val expectedDy = radius * kotlin.math.sin(originalAngle).toFloat()
        
        // السماح بهامش خطأ صغير جداً بسبب الفاصلة العائمة
        assertEquals(expectedDx, dx, 0.001f)
        assertEquals(expectedDy, dy, 0.001f)
    }

    /**
     * اختبار 2: عند زاوية دوران = PI (180 درجة)، يجب أن تنعكس الإحداثيات.
     * الكوكب الذي كان في الأعلى (-PI/2) سيصبح في الأسفل (PI/2).
     */
    @Test
    fun orbitRotation_180Degrees_invertsPosition() {
        val index = 0
        val childCount = 6
        val orbitAngle = PI // 180 degrees
        
        val dx = GalaxyGeometry.childCenterDxDpWithOrbit(index, childCount, orbitAngle)
        val dy = GalaxyGeometry.childCenterDyDpWithOrbit(index, childCount, orbitAngle)
        
        val radius = GalaxyGeometry.childRingRadiusDp(index)
        // الزاوية الأصلية -PI/2 + PI = PI/2 (أسفل الدائرة)
        // cos(PI/2) = 0, sin(PI/2) = 1
        val expectedDx = radius * kotlin.math.cos(PI / 2.0).toFloat()
        val expectedDy = radius * kotlin.math.sin(PI / 2.0).toFloat()
        
        assertEquals(expectedDx, dx, 0.001f)
        assertEquals(expectedDy, dy, 0.001f)
    }

    /**
     * اختبار 3: التأكد من أن سرعة الدوران لا تؤثر على المسافة من المركز (نصف القطر).
     * الكوكب يجب أن يبقى على نفس المدار بغض النظر عن زاوية الدوران.
     */
    @Test
    fun orbitRotation_anyAngle_maintainsOrbitRadius() {
        val index = 2
        val childCount = 12
        val orbitAngle = 1.234 // زاوية عشوائية
        
        val dx = GalaxyGeometry.childCenterDxDpWithOrbit(index, childCount, orbitAngle)
        val dy = GalaxyGeometry.childCenterDyDpWithOrbit(index, childCount, orbitAngle)
        
        // حساب المسافة من المركز: sqrt(dx^2 + dy^2)
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val expectedRadius = GalaxyGeometry.childRingRadiusDp(index)
        
        assertEquals(expectedRadius, distance, 0.001f)
    }

    /**
     * اختبار 4: التأكد من أن الزاوية تُحسب بشكل صحيح للحلقات المختلفة.
     * الحلقات الفردية لها إزاحة (ringOffset = PI/6) لمنع التكدس البصري.
     */
    @Test
    fun orbitRotation_oddRing_hasOffset() {
        val index = 6 // ابن في الحلقة الثانية (index 6 = الحلقة 1, مقعد 0)
        val childCount = 12
        val orbitAngle = 0.0
        
        val angle = GalaxyGeometry.childAngleRadWithOrbit(index, childCount, orbitAngle)
        
        // الحلقة 1 (childRing(6) = 1) يجب أن تحتوي على إزاحة PI/6
        // الزاوية المتوقعة: -PI/2 + PI/6 + 0 * (2*PI/6) = -PI/2 + PI/6
        val expectedAngle = -PI / 2.0 + PI / 6.0
        
        assertEquals(expectedAngle, angle, 0.001)
    }
}
