package com.unihub.app.data.provision

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProvisioningEndpointTest {
    @Test fun acceptsOnlyTheConfiguredHttpsConnectionPath() {
        assertNull(CloudProvisioningClient.endpointOrNull("https://connect.example.com"))
        assertNotNull(CloudProvisioningClient.endpointOrNull("https://connect.example.com/v1/connection"))
        assertNotNull(CloudProvisioningClient.endpointOrNull("https://connect.example.com:443/v1/connection"))
    }

    @Test fun blankOrUnsafeUrlsDisableServiceDelivery() {
        listOf("", "http://connect.example.com/v1/connection",
            "https://user:password@connect.example.com/v1/connection",
            "https://localhost/v1/connection", "https://host.localhost/v1/connection",
            "https://host.local/v1/connection", "https://host.internal/v1/connection",
            "https://127.0.0.1/v1/connection", "https://[::1]/v1/connection", "https://10.0.0.1/v1/connection",
            "https://connect.example.com:444/v1/connection", "https://connect.example.com/other",
            "https://connect.example.com/v1/connection?token=secret",
            "https://connect.example.com/v1/connection#secret"
        ).forEach { assertNull("Accepted unsafe URL: $it", CloudProvisioningClient.endpointOrNull(it)) }
    }
}
