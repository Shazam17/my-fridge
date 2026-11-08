package com.example.myapplication.domain.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlcTest {

    @Test
    fun `сравнение сначала по wall, потом по counter, потом по deviceId`() {
        val a = Hlc(1, 0, "b")
        val b = Hlc(2, 0, "a")
        val c = Hlc(2, 1, "a")
        val d = Hlc(2, 1, "b")
        assertTrue(a < b)
        assertTrue(b < c)
        assertTrue(c < d)
    }

    @Test
    fun `tick монотонен даже если системные часы стоят или откатились`() {
        var clock = 1000L
        val hlc = HlcClock("dev", now = { clock })
        val t1 = hlc.tick()
        val t2 = hlc.tick()
        clock = 500L // часы откатились назад
        val t3 = hlc.tick()
        assertTrue(t1 < t2)
        assertTrue(t2 < t3)
        assertEquals(1000L, t3.wall)
    }

    @Test
    fun `receive делает следующую локальную метку больше удалённой`() {
        val clock = HlcClock("local", now = { 1000L })
        val remote = Hlc(wall = 5000L, counter = 7, deviceId = "other")
        clock.receive(remote)
        val next = clock.tick()
        assertTrue("next=$next должна быть больше remote=$remote", next > remote)
    }

    @Test
    fun `receive с тем же wall берёт максимум counter`() {
        val clock = HlcClock("local", now = { 1000L })
        clock.tick() // wall=1000, counter=0
        clock.receive(Hlc(1000L, 4, "x")) // receive выставил counter=5
        val next = clock.tick()             // tick при том же wall даёт counter=6
        assertEquals(1000L, next.wall)
        assertEquals(6, next.counter)
        assertTrue(next > Hlc(1000L, 4, "x"))
    }
}
