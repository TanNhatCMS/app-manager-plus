package com.app.manager.data.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseAssetPickerTest {

    private fun assets(tag: String) = listOf(
        ReleaseAsset("microg-$tag.apk", "u/$tag.apk"),
        ReleaseAsset("microg-$tag-noicon.apk", "u/$tag-noicon.apk"),
        ReleaseAsset("microg-$tag-icon-arm64-v8a.apk", "a64/$tag.apk"),
        ReleaseAsset("microg-$tag-icon-armeabi-v7a.apk", "a7/$tag.apk"),
        ReleaseAsset("microg-$tag-noicon-arm64-v8a.apk", "a64n/$tag.apk"),
        ReleaseAsset("microg-$tag-noicon-armeabi-v7a.apk", "a7n/$tag.apk")
    )

    private val releases = listOf(
        GitHubRelease("7.3.1-dev.2", prerelease = true, publishedAtMs = 200L, assets = assets("7.3.1-dev.2")),
        GitHubRelease("7.3.1", prerelease = false, publishedAtMs = 100L, assets = assets("7.3.1")),
        GitHubRelease("7.3.0", prerelease = false, publishedAtMs = 50L, assets = assets("7.3.0"))
    )

    @Test
    fun `stable channel picks the newest non-prerelease release`() {
        val (release, asset) = ReleaseAssetPicker.pick(releases, wantPrerelease = false, abiTag = "arm64-v8a", noicon = false)!!
        assertEquals("7.3.1", release.tag)
        assertEquals("a64/7.3.1.apk", asset.url)
    }

    @Test
    fun `beta channel picks the newest prerelease`() {
        val (release, asset) = ReleaseAssetPicker.pick(releases, wantPrerelease = true, abiTag = "arm64-v8a", noicon = false)!!
        assertEquals("7.3.1-dev.2", release.tag)
        assertEquals("a64/7.3.1-dev.2.apk", asset.url)
    }

    @Test
    fun `arm64 device gets the arm64 variant`() {
        val (_, asset) = ReleaseAssetPicker.pick(releases, wantPrerelease = false, abiTag = "arm64-v8a", noicon = false)!!
        assertEquals("a64/7.3.1.apk", asset.url)
    }

    @Test
    fun `32-bit device gets the armeabi-v7a variant`() {
        val (_, asset) = ReleaseAssetPicker.pick(releases, wantPrerelease = false, abiTag = "armeabi-v7a", noicon = false)!!
        assertEquals("a7/7.3.1.apk", asset.url)
    }

    @Test
    fun `unknown ABI falls back to the universal APK`() {
        val (_, asset) = ReleaseAssetPicker.pick(releases, wantPrerelease = false, abiTag = null, noicon = false)!!
        assertEquals("u/7.3.1.apk", asset.url)
    }

    @Test
    fun `noicon flag picks the icon-hidden variant`() {
        val (_, asset) = ReleaseAssetPicker.pick(releases, wantPrerelease = false, abiTag = "arm64-v8a", noicon = true)!!
        assertEquals("a64n/7.3.1.apk", asset.url)
        val (_, universal) = ReleaseAssetPicker.pick(releases, wantPrerelease = false, abiTag = null, noicon = true)!!
        assertEquals("u/7.3.1-noicon.apk", universal.url)
    }

    @Test
    fun `missing prerelease falls back to nothing rather than a stable build`() {
        val stableOnly = releases.filter { !it.prerelease }
        assertNull(ReleaseAssetPicker.pick(stableOnly, wantPrerelease = true, abiTag = "arm64-v8a", noicon = false))
    }
}
