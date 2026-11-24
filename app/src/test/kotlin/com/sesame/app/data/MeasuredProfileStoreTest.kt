package com.sesame.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MeasuredProfileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val release = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"

    private fun capture(
        releaseLine: String = release,
        slabLine: String = "mm_struct 1234 1300 960 34 8 : tunables 0 0 0 : slabdata 39 39 0",
    ): String = "# $releaseLine\n$slabLine\n"

    private fun newStore(name: String = "measured_profiles.conf"): MeasuredProfileStore =
        MeasuredProfileStore(File(temporaryFolder.root, name))

    @Test
    fun `parses the root capture into the stride fact`() {
        val fact = MeasuredProfileStore.parseSlabinfo(release, capture(), now = 42L)
        assertNotNull(fact)
        assertEquals(MeasuredProfileStore.KeyMmStructSz, fact!!.key)
        assertEquals(960L, fact.value)
        assertEquals(MeasuredProfileStore.SourceRootSlabinfo, fact.source)
        assertTrue(fact.evidence.startsWith("mm_struct "))
        assertEquals(42L, fact.measuredAt)
    }

    @Test
    fun `refuses a capture from another kernel`() {
        assertNull(MeasuredProfileStore.parseSlabinfo(release, capture(releaseLine = "5.10.81")))
    }

    @Test
    fun `refuses a capture without the mm_struct line`() {
        val raw = "# $release\ncred_jar 100 100 192 42 2 : tunables 0 0 0\n"
        assertNull(MeasuredProfileStore.parseSlabinfo(release, raw))
    }

    @Test
    fun `refuses an implausible object size`() {
        val raw = capture(slabLine = "mm_struct 1 1 7 34 8 : tunables 0 0 0")
        assertNull(MeasuredProfileStore.parseSlabinfo(release, raw))
    }

    @Test
    fun `records and reloads a fact`() {
        val store = newStore()
        val fact = MeasuredProfileStore.parseSlabinfo(release, capture(), now = 7L)!!
        assertTrue(store.record(release, fact))

        val reloaded = newStore().kernel(release)
        assertNotNull(reloaded)
        assertEquals(7L, reloaded!!.updatedAt)
        val loadedFact = reloaded.fact(MeasuredProfileStore.KeyMmStructSz)
        assertNotNull(loadedFact)
        assertEquals(960L, loadedFact!!.value)
        assertEquals(MeasuredProfileStore.SourceRootSlabinfo, loadedFact.source)
        assertTrue(loadedFact.evidence.contains("mm_struct"))
    }

    @Test
    fun `a second measurement replaces the same key`() {
        val store = newStore()
        store.record(release, MeasuredProfileStore.parseSlabinfo(release, capture(), now = 1L)!!)
        val second = MeasuredProfileStore.parseSlabinfo(
            release,
            capture(slabLine = "mm_struct 10 20 1024 32 8 : tunables 0 0 0"),
            now = 2L,
        )!!
        assertTrue(store.record(release, second))

        val kernel = store.kernel(release)!!
        assertEquals(1, kernel.facts.size)
        assertEquals(1024L, kernel.facts.single().value)
        assertEquals(2L, kernel.updatedAt)
    }

    @Test
    fun `keeps kernels independent`() {
        val store = newStore()
        store.record(release, MeasuredProfileStore.parseSlabinfo(release, capture(), now = 1L)!!)
        val other = "5.10.101-android12-9-gdeadbeef"
        store.record(
            other,
            MeasuredProfileStore.parseSlabinfo(
                other,
                capture(releaseLine = other, slabLine = "mm_struct 1 1 1024 32 8 : tunables 0 0 0"),
                now = 2L,
            )!!,
        )
        assertEquals(2, store.kernels().size)
        assertEquals(960L, store.kernel(release)!!.facts.single().value)
        assertEquals(1024L, store.kernel(other)!!.facts.single().value)
    }

    @Test
    fun `a newer schema is never overwritten`() {
        val file = File(temporaryFolder.root, "measured_profiles.conf")
        file.writeText("schema_version = 2\nkernels = []\n")
        val before = file.readText()

        val store = MeasuredProfileStore(file)
        val fact = MeasuredProfileStore.parseSlabinfo(release, capture(), now = 1L)!!
        assertFalse(store.record(release, fact))
        assertEquals(before, file.readText())
        assertTrue(store.kernels().isEmpty())
    }

    @Test
    fun `records a clear probe with rom and protocol and reuses it`() {
        val store = newStore()
        val rom = "Redmi/diting/diting:15/AQ3A.250226.002/OS3.0.3.0.VLFCNXM:user/release-keys"
        assertTrue(
            store.recordDirtyfragProbe(
                release, DirtyfragProbe.Outcome.Pass, rom, "control=pass block=...", now = 5L,
            ),
        )
        val kernel = store.kernel(release)!!
        assertEquals(3, kernel.facts.size)
        assertEquals(1L, kernel.fact(MeasuredProfileStore.KeyDirtyfragProbe)!!.value)
        assertEquals(
            MeasuredProfileStore.romHash(rom),
            kernel.fact(MeasuredProfileStore.KeyDirtyfragProbeRom)!!.value,
        )
        assertEquals(
            DirtyfragProbe.ProtocolVersion,
            kernel.fact(MeasuredProfileStore.KeyDirtyfragProbeProtocol)!!.value,
        )
        assertEquals(ProbeReuse.Pass, store.probeReuse(release, rom))
        assertEquals(ProbeReuse.None, store.probeReuse(release, "another-rom"))
        assertEquals(ProbeReuse.None, store.probeReuse(release, rom, protocol = 2L))
    }

    @Test
    fun `a primitive fail is reused without the rom match`() {
        val store = newStore()
        store.recordDirtyfragProbe(
            release, DirtyfragProbe.Outcome.PrimitiveFailed, "rom-a", "control=pass", now = 1L,
        )
        assertEquals(ProbeReuse.PrimitiveFailed, store.probeReuse(release, "rom-b"))
    }

    @Test
    fun `setup failures and crashes are not recorded`() {
        val store = newStore()
        assertFalse(
            store.recordDirtyfragProbe(
                release, DirtyfragProbe.Outcome.SetupFailed, "rom", "", now = 1L,
            ),
        )
        assertFalse(
            store.recordDirtyfragProbe(
                release, DirtyfragProbe.Outcome.Crashed, "rom", "", now = 1L,
            ),
        )
        assertNull(store.kernel(release))
    }

    @Test
    fun `the probe facts coexist with the slabinfo fact`() {
        val store = newStore()
        store.record(release, MeasuredProfileStore.parseSlabinfo(release, capture(), now = 1L)!!)
        store.recordDirtyfragProbe(
            release, DirtyfragProbe.Outcome.Pass, "rom", "control=pass", now = 2L,
        )
        val kernel = store.kernel(release)!!
        assertEquals(4, kernel.facts.size)
        assertEquals(
            960L,
            kernel.fact(MeasuredProfileStore.KeyMmStructSz)!!.value,
        )
        /* Only the profile-addressable fact is mergeable; the probe trio stays
         * visible in the measurements screen and out of every merge. */
        assertEquals(
            listOf(MeasuredProfileStore.KeyMmStructSz),
            kernel.profileFacts().map { it.key },
        )
    }

    @Test
    fun `an unreadable file is not silently replaced`() {
        val file = File(temporaryFolder.root, "measured_profiles.conf")
        file.writeText("this is not hocon {{{")
        val store = MeasuredProfileStore(file)
        assertFalse(store.record(release, MeasuredProfileStore.parseSlabinfo(release, capture())!!))
    }

    @Test
    fun `a generation probe records the stride when no root measurement exists`() {
        val store = newStore()

        assertTrue(store.recordProbeStride(release, 1280, "probe gcd=0x400 rounds=3 samples=2"))

        val fact = newStore().kernel(release)!!.fact(MeasuredProfileStore.KeyMmStructSz)!!
        assertEquals(1280L, fact.value)
        assertEquals(MeasuredProfileStore.SourceGenerationProbe, fact.source)
        assertTrue(fact.evidence.contains("gcd"))
        /* Weaker evidence: the generated profile carries the value itself, so
         * the probe is never applied by the overlay nor merged into an export. */
        assertTrue(newStore().kernel(release)!!.profileFacts().isEmpty())
    }

    @Test
    fun `a root measurement is never replaced by a generation probe`() {
        val store = newStore()
        store.record(release, MeasuredProfileStore.parseSlabinfo(release, capture(), now = 1L)!!)

        assertFalse(store.recordProbeStride(release, 1280, "probe"))

        val fact = newStore().kernel(release)!!.fact(MeasuredProfileStore.KeyMmStructSz)!!
        assertEquals(960L, fact.value)
        assertEquals(MeasuredProfileStore.SourceRootSlabinfo, fact.source)
    }

    @Test
    fun `a later generation probe replaces an earlier one`() {
        val store = newStore()
        assertTrue(store.recordProbeStride(release, 1024, "probe 1"))
        assertTrue(store.recordProbeStride(release, 1280, "probe 2"))

        val fact = newStore().kernel(release)!!.fact(MeasuredProfileStore.KeyMmStructSz)!!
        assertEquals(1280L, fact.value)
        assertEquals("probe 2", fact.evidence)
    }

    @Test
    fun `an implausible generation probe size is refused`() {
        val store = newStore()
        assertFalse(store.recordProbeStride(release, 7, "garbled"))
        assertNull(newStore().kernel(release))
    }

    @Test
    fun `a retired-key fact is dropped on load and never merged`() {
        val file = File(temporaryFolder.root, "measured_profiles.conf")
        file.writeText(
            """
            schema_version = 1
            kernels = [
              {
                release = "$release"
                updated_at = 1
                facts = [
                  { key = "kernelsnitch.mm_struct_sz", value = 960, source = "root-slabinfo", evidence = "old", measured_at = 1 }
                  { key = "kernel.heap.stride", value = 960, source = "root-slabinfo", evidence = "mm_struct", measured_at = 1 }
                ]
              }
            ]
            """.trimIndent() + "\n",
        )
        val store = MeasuredProfileStore(file)

        val kernel = store.kernel(release)!!
        assertEquals(listOf(MeasuredProfileStore.KeyMmStructSz), kernel.facts.map { it.key })
        assertEquals(listOf(MeasuredProfileStore.KeyMmStructSz), kernel.profileFacts().map { it.key })
        assertFalse(file.readText().contains("kernelsnitch.mm_struct_sz"))
    }
}
