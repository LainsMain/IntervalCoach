package com.intervalcoach.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseParseTest {
    @Test fun acceptsOnlyMatchingApkWithDigest() {
        val sha = "a".repeat(64)
        val json = """{"tag_name":"v1.2.0","assets":[
          {"name":"source.zip","browser_download_url":"https://github.com/example/source.zip","size":5},
          {"name":"IntervalCoach-v1.2.0.apk","browser_download_url":"https://github.com/LainsMain/IntervalCoach/releases/download/v1.2.0/IntervalCoach-v1.2.0.apk","digest":"sha256:$sha","size":25000000}
        ]}"""
        val release = parseLatestRelease(json)
        assertEquals("1.2.0", release.version)
        assertEquals(sha, release.sha256)
        assertEquals(25_000_000, release.size)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingDigest() {
        parseLatestRelease("""{"tag_name":"v1.2.0","assets":[{"name":"IntervalCoach-v1.2.0.apk","browser_download_url":"https://github.com/LainsMain/IntervalCoach/releases/download/v1.2.0/a.apk","size":20}]}""")
    }
}
