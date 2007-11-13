package org.hivedb.hibernate;

import org.hivedb.hibernate.annotations.EntityId;
import org.hivedb.hibernate.annotations.Index;
import org.hivedb.hibernate.annotations.PartitionIndex;
import org.hivedb.hibernate.annotations.Resource;

@Resource(name=WeatherReport.CONTINENT)
public interface Continent {
	@EntityId
	@PartitionIndex(name=WeatherReport.CONTINENT)
//	@PartitionIndex
	public String getName();
	public void setName(String name);
	@Index
	public Integer getPopulation();
	public void setPopulation(Integer population);
}
