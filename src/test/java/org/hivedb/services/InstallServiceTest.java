package org.hivedb.services;

import org.hivedb.Hive;
import org.hivedb.HiveRuntimeException;
import org.hivedb.Lockable.Status;
import org.hivedb.Schema;
import org.hivedb.management.HiveConfigurationSchemaInstaller;
import org.hivedb.meta.Node;
import org.hivedb.meta.persistence.CachingDataSourceProvider;
import org.hivedb.meta.persistence.TableInfo;
import org.hivedb.util.database.HiveDbDialect;
import org.hivedb.util.database.Schemas;
import org.hivedb.util.database.test.ContinentalSchema;
import org.hivedb.util.database.test.H2TestCase;
import org.hivedb.util.database.test.WeatherSchema;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Test;

import java.sql.Types;
import java.util.Arrays;
import java.util.Collection;

public class InstallServiceTest extends H2TestCase {

  @Override
  @Before
  public void beforeMethod() {
    this.deleteDatabasesAfterEachTest = true;
    super.afterMethod();
    super.beforeMethod();
    new HiveConfigurationSchemaInstaller(uri()).run();
    Hive.create(uri(), "split", Types.INTEGER, CachingDataSourceProvider.getInstance(), null);
  }

  @Test
  public void installANewNodeWithSchema() throws Exception {
    Schema schema = WeatherSchema.getInstance();
    String nodeName = "aNewNode";
    getService().install(schema.getName(), nodeName, H2TestCase.TEST_DB, "unecessary for H2", "H2", "na", "na");
    validateSchema(schema, Hive.load(uri(), CachingDataSourceProvider.getInstance()).getNode(nodeName));
  }

  @Test
  public void installASchemaOnAnExistingNode() throws Exception {
    Hive hive = Hive.load(uri(), CachingDataSourceProvider.getInstance());
    String nodeName = "anExistingNode";
    Node node = new Node(nodeName, H2TestCase.TEST_DB, "unecessary", HiveDbDialect.H2);
    hive.addNode(node);
    WeatherSchema weatherSchema = WeatherSchema.getInstance();
    getService().install(weatherSchema.getName(), nodeName);
    validateSchema(weatherSchema, node);
  }

  @Test
  public void tryToInstallToAReadOnlyHive() throws Exception {
    Hive hive = Hive.load(uri(), CachingDataSourceProvider.getInstance());
    hive.updateHiveStatus(Status.readOnly);

    try {
      getService().install(WeatherSchema.getInstance().getName(), "aNewNode", H2TestCase.TEST_DB, "unecessary for H2", "H2", "na", "na");

      fail("No exception Thrown");
    } catch (HiveRuntimeException e) {
      //pass
    }
  }

  private void validateSchema(Schema schema, Node node) {
    for (TableInfo t : schema.getTables(node.getUri()))
      assertTrue(Schemas.tableExists(t.getName(), node.getUri()));
  }

  private String uri() {
    return getConnectString(H2TestCase.TEST_DB);
  }

  private Collection<Schema> getSchemata() {
    return Arrays.asList(new Schema[]{
      WeatherSchema.getInstance(),
      ContinentalSchema.getInstance()
    });
  }

  private InstallServiceImpl getService() {
    return new InstallServiceImpl(getSchemata(), Hive.load(uri(), CachingDataSourceProvider.getInstance()));
  }

  @Override
  public Collection<String> getDatabaseNames() {
    return Arrays.asList(new String[]{H2TestCase.TEST_DB});
  }

}
