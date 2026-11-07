package com.nareshjagriya.tvtown;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WeatherTest {

    @Test
    public void weatherCodesBecomeSymbols() {
        assertEquals("☀️", Weather.symbol(0, true));
        assertEquals("🌙", Weather.symbol(0, false));
        assertEquals("☁️", Weather.symbol(3, true));
        assertEquals("🌫️", Weather.symbol(45, true));
        assertEquals("🌧️", Weather.symbol(61, true));
        assertEquals("🌧️", Weather.symbol(81, false));
        assertEquals("❄️", Weather.symbol(73, true));
        assertEquals("⛈️", Weather.symbol(95, true));
    }
}
