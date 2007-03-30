package org.hivedb.management.statistics;

import static org.testng.AssertJUnit.assertEquals;

import java.util.ArrayList;
import java.util.Collection;

import org.testng.annotations.Test;

public class TestRollingAverageStatistics {
	@Test
	public void testGet() {
		RollingAverageStatistics stats = new RollingAverageStatisticsImpl(testKeys(), 1000, 10);
		
		for(String key: testKeys())
			stats.add(key, 10);
		
		for(String key: testKeys())
			assertEquals(10L, stats.getAverage(key));
	}
	@Test
	public void testGetWindowSizeAndInterval() {
		RollingAverageStatistics stats = new RollingAverageStatisticsImpl(testKeys(), 1000, 10);
		
		for(String key : testKeys()) {
			assertEquals(1000, stats.getWindow(key));
			assertEquals(10, stats.getInterval(key));
		}
	}
	@Test
	public void testAverage() throws Exception {
		RollingAverageStatistics stats = new RollingAverageStatisticsImpl(testKeys(), 1000, 10);
		
		for(String key: testKeys()) {
			stats.add(key, 10);
			Thread.sleep(13);
			stats.add(key, 20);
		}
		
		for(String key: testKeys())
			assertEquals(15, stats.getAverage(key));
	}
	@Test
	public void testIncrementAndDecrement() {
		RollingAverageStatistics stats = new RollingAverageStatisticsImpl(testKeys(), 1000, 100);
		
		for(String key: testKeys()) {
			stats.increment(key);
		}
		
		for(String key: testKeys())
			assertEquals(1, stats.getAverage(key));
		
		for(String key: testKeys())
			stats.decrement(key);
		
		for(String key: testKeys())
			assertEquals(0, stats.getAverage(key));
	}
	
	@Test
	public void testComputedStatistics() throws Exception{
		RollingAverageStatistics stats = new RollingAverageStatisticsImpl(testKeys(), 1000, 10);
		String key = testKeys().iterator().next();
		
		for(int i=0; i<5; i++) {
			stats.add(key, 10+i*10);
			Thread.sleep(11);
		}
		
		assertEquals(10l, stats.getMin(key));
		assertEquals(50l, stats.getMax(key));
		assertEquals(200.0, stats.getVariance(key), 0.1f);
		assertEquals(150l, stats.getTimeAverage(key, 1000));
	}
	
	private Collection<String> testKeys() {
		Collection<String> keys = new ArrayList<String>();
		String key1 = "test.stat.1";
		keys.add(key1);
		String key2 = "test.stat.2";
		keys.add(key2);
		String key3 = "test.stat.3";
		keys.add(key3);
		return keys;
	}
}
