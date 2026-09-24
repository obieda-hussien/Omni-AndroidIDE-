package dev.mutwakil.androidide.omni

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniIdeCallerPolicyTest {
    @Test
    fun adminRequiresVerifiedSignerAndDistinctPackage() {
        assertTrue(
            OmniIdeCallerPolicy.allowed(
                "com.omnidev.workspace.admin", true, "ide.apply_line_patch"
            )
        )
        assertFalse(
            OmniIdeCallerPolicy.allowed(
                "com.omnidev.workspace.admin", false, "ide.apply_line_patch"
            )
        )
        assertFalse(
            OmniIdeCallerPolicy.allowed(
                "com.evil.workspace.admin", true, "ide.apply_line_patch"
            )
        )
    }

    @Test
    fun normalBuildsNeverGetAdminMutations() {
        listOf(
            "com.omnidev.workspace",
            "com.omnidev.workspace.norm",
            "com.omnidev.workspace.pro",
            "com.omnidev.workspace.oem"
        ).forEach {
            assertFalse(OmniIdeCallerPolicy.allowed(it, true, "ide.git_push"))
        }
        assertTrue(OmniIdeCallerPolicy.allowed("com.omnidev.workspace.pro", true, "ide.start_build"))
        assertFalse(OmniIdeCallerPolicy.allowed("com.omnidev.workspace", true, "ide.start_build"))
    }

    @Test
    fun eventSubscriptionsRequireRecognizedSignedWorkspace() {
        listOf(
            "com.omnidev.workspace",
            "com.omnidev.workspace.norm",
            "com.omnidev.workspace.pro",
            "com.omnidev.workspace.oem",
            "com.omnidev.workspace.admin"
        ).forEach {
            assertTrue(OmniIdeCallerPolicy.allowed(it, true, "register_event_listener"))
            assertFalse(OmniIdeCallerPolicy.allowed(it, false, "register_event_listener"))
        }
        assertFalse(
            OmniIdeCallerPolicy.allowed("com.unknown.app", true, "register_event_listener")
        )
        assertFalse(
            OmniIdeCallerPolicy.allowed("com.omnidev.workspace.admin", true, "unregister_event_listener")
        )
    }

    @Test
    fun unknownPeerNeverReadsIdeProject() {
        assertFalse(OmniIdeCallerPolicy.allowed("com.unknown.app", true, "ide.get_project_context"))
        assertFalse(OmniIdeCallerPolicy.allowed("com.unknown.app", false, "ide.get_project_context"))
    }
}
