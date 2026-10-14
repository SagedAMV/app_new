package com.unihub.app.data.appwrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppwriteAuthBridgeErrorTest {
    @Test
    fun missingFunctionIsReportedAsNotDeployed() {
        assertEquals("bridge_not_deployed", bridgeErrorCodeFor(404, "function_not_found"))
    }

    @Test
    fun otherNotFoundErrorsStayGeneric() {
        assertEquals("bridge_unavailable", bridgeErrorCodeFor(404, "deployment_not_found"))
        assertEquals("bridge_unavailable", bridgeErrorCodeFor(404, null))
    }

    @Test
    fun sessionAndPermissionFailuresKeepTheirCodes() {
        assertEquals("unauthorized", bridgeErrorCodeFor(401, "user_unauthorized"))
        assertEquals("forbidden", bridgeErrorCodeFor(403, "user_unauthorized"))
        assertEquals("bridge_unavailable", bridgeErrorCodeFor(null, null))
        assertEquals("bridge_unavailable", bridgeErrorCodeFor(500, "general_unknown"))
    }

    @Test
    fun notDeployedMessageIsSpecific() {
        val notDeployed = AppwriteBridgeException(404, "bridge_not_deployed").message
        val generic = AppwriteBridgeException(503, "bridge_unavailable").message
        assertNotEquals(generic, notDeployed)
        assertEquals(true, notDeployed!!.contains("غير منشورة"))
    }
}
