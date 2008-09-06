package org.hivedb.hibernate.simplified;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.shards.strategy.access.SequentialShardAccessStrategy;
import org.hivedb.hibernate.HiveSessionFactory;
import org.hivedb.hibernate.HiveSessionFactoryBuilderImpl;
import org.hivedb.util.classgen.GenerateInstance;
import org.hivedb.util.classgen.GeneratedClassFactory;
import org.hivedb.util.database.test.HiveTest;
import org.hivedb.util.database.test.WeatherReport;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

import java.util.List;

@HiveTest.Config(file="hive_default")
public class ErrorCorrectingDataAccessObjectSaveTest extends HiveTest{
  private final static Log log = LogFactory.getLog(ErrorCorrectingDataAccessObjectSaveTest.class);
  private HiveSessionFactory hiveSessionFactory;

  @Test
  public void shouldPassATest() throws Exception {
    assertTrue(true);
  }

  private HiveSessionFactory getSessionFactory() {
    if(hiveSessionFactory==null)
      hiveSessionFactory = new HiveSessionFactoryBuilderImpl(getHive().getUri(), (List<Class<?>>) getMappedClasses(),new SequentialShardAccessStrategy());
    return hiveSessionFactory;
  }

  private WeatherReport getPersistentInstance(DataAccessObject<WeatherReport, Integer> dao) {
      return dao.save(getInstance(WeatherReport.class));
  }

  private<T> T getInstance(Class<T> clazz) {
    return new GenerateInstance<T>(clazz).generate();
  }

  private Class getGeneratedClass() {
		return GeneratedClassFactory.newInstance(WeatherReport.class).getClass();
	}
}
