/**
 * HiveDB is an Open Source (LGPL) system for creating large, high-transaction-volume
 * data storage systems.
 */
package org.hivedb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.log4j.Logger;
import org.hivedb.management.statistics.DirectoryPerformanceStatistics;
import org.hivedb.management.statistics.DirectoryPerformanceStatisticsMBean;
import org.hivedb.management.statistics.HivePerformanceStatistics;
import org.hivedb.management.statistics.PartitionKeyStatisticsDao;
import org.hivedb.meta.AccessType;
import org.hivedb.meta.Directory;
import org.hivedb.meta.HiveSemaphore;
import org.hivedb.meta.IdAndNameIdentifiable;
import org.hivedb.meta.Identifiable;
import org.hivedb.meta.IndexSchema;
import org.hivedb.meta.Node;
import org.hivedb.meta.NodeSemaphore;
import org.hivedb.meta.PartitionDimension;
import org.hivedb.meta.Resource;
import org.hivedb.meta.SecondaryIndex;
import org.hivedb.meta.persistence.HiveBasicDataSource;
import org.hivedb.meta.persistence.HiveSemaphoreDao;
import org.hivedb.meta.persistence.NodeDao;
import org.hivedb.meta.persistence.PartitionDimensionDao;
import org.hivedb.meta.persistence.ResourceDao;
import org.hivedb.meta.persistence.SecondaryIndexDao;
import org.hivedb.util.DriverLoader;
import org.hivedb.util.HiveUtils;
import org.hivedb.util.IdentifiableUtils;

/**
 * @author Kevin Kelm (kkelm@fortress-consulting.com)
 * @author Andy Likuski (alikuski@cafepress.com)
 * @author Britt Crawford (bcrawford@cafepress.com)
 */
public class Hive implements Synchronizeable {
	private static Logger log = Logger.getLogger(Hive.class);
	public static final int NEW_OBJECT_ID = 0;
	public static String URI_SYSTEM_PROPERTY = "org.hivedb.uri";
	private String hiveUri;
	private int revision;
	private boolean readOnly;
	private boolean performanceMonitoringEnabled = true;
	private Collection<PartitionDimension> partitionDimensions;
	private PartitionKeyStatisticsDao partitionStatistics;
	private Collection<Directory> directories;
	private HiveSyncDaemon daemon;
	private DataSource dataSource;
	private Map<String, JdbcDaoSupportCacheImpl> jdbcDaoSupportCaches;
	private HivePerformanceStatistics performanceStatistics;
	
	/**
	 * System entry point. Factory method for all Hive interaction. If the first
	 * attempt to load the Hive throws any exception, the Hive class will
	 * attempt to install the database schema at the target URI, and then
	 * attempt to reload that schema one time before throwing a HiveException.
	 *
	 * @param hiveDatabaseUri
	 *            Target hive
	 * @return Hive (existing or new) located at hiveDatabaseUri
	 * @throws HiveException
	 */
	public static Hive load(String hiveDatabaseUri) throws HiveException {
		if (log.isDebugEnabled())
			log.debug("Loading Hive from " + hiveDatabaseUri);
		
		//Tickle driver
		try {
			DriverLoader.loadByDialect(DriverLoader
					.discernDialect(hiveDatabaseUri));
		} catch (ClassNotFoundException e) {
			throw new HiveException("Unable to load database driver: "
					+ e.getMessage(), e);
		} 
		
		Hive hive = null;
		HiveBasicDataSource ds = new HiveBasicDataSource(hiveDatabaseUri);
		try {
			hive = new Hive(hiveDatabaseUri, 0, false,
					new ArrayList<PartitionDimension>(), new PartitionKeyStatisticsDao(ds));
			hive.sync();
			if (log.isDebugEnabled())
				log.debug("Successfully loaded Hive from " + hiveDatabaseUri);
		// TODO: catch a more specific exception here
		} catch (Exception ex) {
			log.warn("No HiveDB global schema detected (" + ex.getMessage() +  ") at : "
					+ hiveDatabaseUri);
			for (StackTraceElement s : ex.getStackTrace())
				log.warn(s.toString());

			throw new HiveRuntimeException("Hive metadata is not installed.  Run the Hive installer.");
		}
		return hive;
	}
	
	/***
	 * Alternate system entry point, using this load method enables runtime statistics tracking.
	 * Factory method for all Hive interaction. If the first
	 * attempt to load the Hive throws any exception, the Hive class will
	 * attempt to install the database schema at the target URI, and then
	 * attempt to reload that schema one time before throwing a HiveException.
	 * 
	 * @param hiveDatabaseUri
	 * @param hiveStats
	 * @param directoryStats
	 * @return
	 * @throws HiveException
	 */
	public static Hive load(String hiveDatabaseUri, HivePerformanceStatistics hiveStats, DirectoryPerformanceStatistics directoryStats) throws HiveException {
		Hive hive = Hive.load(hiveDatabaseUri);
		hive.setPerformanceStatistics(hiveStats);
		hive.setPerformanceMonitoringEnabled(true);
		
		for(Directory dir : hive.directories){
			dir.setPerformanceStatistics((DirectoryPerformanceStatisticsMBean)directoryStats);
			dir.setPerformanceMonitoringEnabled(true);
		}
		return hive;
	}


	/**
	 * Explicitly syncs the hive with the persisted data, rather than waiting
	 * for the periodic sync.
	 * 
	 * @throws HiveException
	 * 
	 */
	public void sync() throws HiveException {
		daemon.forceSynchronize();
		
		// Synchronize the collection of Synchronizeable JdbcDaoSupportCaches
	
		Collection<String> dimensionNames = new ArrayList<String>();
		for(PartitionDimension dimension : getPartitionDimensions())
			dimensionNames.add(dimension.getName());
		
		//Build a set of caches that have been removed
		Set<String> exclusions = new HashSet<String>(jdbcDaoSupportCaches.keySet());
		exclusions.removeAll(dimensionNames);
		//Delete them
		for(String name : exclusions)
			jdbcDaoSupportCaches.remove(name);
		
		for(String name : dimensionNames) {
			if(jdbcDaoSupportCaches.containsKey(name))
				jdbcDaoSupportCaches.get(name).sync();
			else {
				if(isPerformanceMonitoringEnabled())
					jdbcDaoSupportCaches.put(name, new JdbcDaoSupportCacheImpl(name, this, getDirectory(getPartitionDimension(name)), performanceStatistics));
				else
					jdbcDaoSupportCaches.put(name, new JdbcDaoSupportCacheImpl(name, this, getDirectory(getPartitionDimension(name))));
			}
		}
	}

	/**
	 * INTERNAL USE ONLY- load the Hive from persistence
	 * 
	 * @param revision
	 * @param readOnly
	 */
	protected Hive(String hiveUri, int revision, boolean readOnly,
			Collection<PartitionDimension> partitionDimensions,
			PartitionKeyStatisticsDao statistics) {
		this.hiveUri = hiveUri;
		this.revision = revision;
		this.readOnly = readOnly;
		this.partitionDimensions = partitionDimensions;
		this.partitionStatistics = statistics;
		this.daemon = new HiveSyncDaemon(this);
		this.dataSource = new HiveBasicDataSource(this.getHiveUri());

		this.directories = new ArrayList<Directory>();
		
		jdbcDaoSupportCaches = new ConcurrentHashMap<String, JdbcDaoSupportCacheImpl>();
		for (PartitionDimension dimension : this.partitionDimensions) {
			this.directories.add(new Directory(dimension, this.dataSource));
			if(isPerformanceMonitoringEnabled())
				jdbcDaoSupportCaches.put(dimension.getName(), new JdbcDaoSupportCacheImpl(dimension.getName(), this, getDirectory(dimension), performanceStatistics));
			else
				jdbcDaoSupportCaches.put(dimension.getName(), new JdbcDaoSupportCacheImpl(dimension.getName(), this, getDirectory(dimension)));
		}
	}

	/**
	 * the URI of the hive database where all meta data is stored for this hive.
	 */
	public String getHiveUri() {
		return hiveUri;
	}

	/**
	 * A member of the HiveDbDialect enumeration corresponding to the underlying
	 * database type
	 */
	public HiveDbDialect getDialect() {
		return DriverLoader.discernDialect(hiveUri);
	}

	/**
	 * Hives are uniquely hashed by their URI, revision, partition dimensions,
	 * and read-only state
	 */
	public int hashCode() {
		return HiveUtils.makeHashCode(new Object[] { hiveUri, revision, getPartitionDimensions(), readOnly });
	}

	public boolean equals(Object obj) {
		return hashCode() == obj.hashCode();
	}

	/**
	 * Indicates whether or not the hive metatables and indexes may be updated.
	 * 
	 * @return Returns true if the hive is in a read-only state.
	 */
	public boolean isReadOnly() {
		return readOnly;
	}

	/**
	 * INTERNAL USE ONLY - use updateHiveReadOnly to persist the hive's read
	 * only status Make the hive hive read-only, meaning hive metatables and
	 * indexes may not be updated.
	 * 
	 * @param readOnly
	 */
	// TODO: If we kill the daemon we can kill this method
	protected void setReadOnly(boolean readOnly) {
		this.readOnly = readOnly;
	}

	/***
	 * Set whether or not the Hive is read-only.
	 * @param readOnly true == read-only, false == read-write
	 */
	public void updateHiveReadOnly(Boolean readOnly) {
		this.setReadOnly(readOnly);
		new HiveSemaphoreDao(new HiveBasicDataSource(this.getHiveUri())).update(new HiveSemaphore(
				readOnly, this.getRevision()));
	}

	/***
	 * Set the read-only status of a particular node.
	 * @param node Target node
	 * @param readOnly true == read-only, false == read-write
	 * @throws HiveException
	 */
	public void updateNodeReadOnly(Node node, Boolean readOnly) throws HiveException {
		node.setReadOnly(readOnly);
		this.updateNode(node);
	}
	
	/**
	 * Get the current revision of the hive. The revision number is increased
	 * when new indexes are added to the hive or if the schema of an index is
	 * altered.
	 * 
	 * @return The current revision number of the hive.
	 */
	public int getRevision() {
		return revision;
	}

	/**
	 * INTERNAL USE ONLY - sets the current hive revision.
	 * 
	 * @param revision
	 */
	// TODO: If we kill the daemon we can kill this method
	protected void setRevision(int revision) {
		this.revision = revision;
	}

	/**
	 * Gets all partition dimensions of the hive. A PartitionDimension instance
	 * references all of its underlying components--its NodeGroup and Resources.
	 * 
	 * @return
	 */
	public Collection<PartitionDimension> getPartitionDimensions() {
		return partitionDimensions;
	}
	
	/**
	 * Gets a partition dimension by name.
	 * 
	 * @param name
	 *            The user-defined name of a partition dimension
	 * @return
	 * @throws IllegalArgumentException
	 *             Thrown if no parition dimension with the given name exists.
	 *             To avoid this exception, test for existence first with
	 */
	public PartitionDimension getPartitionDimension(String name) {
		PartitionDimension dimension = getPartitionDimensionOrNull(name);
		if (dimension == null)
			throw new IllegalArgumentException("PartitionDimension with name "
					+ name + " not found.");
		return dimension;
	}
	
	/**
	 * Test for existence of this partition dimension name
	 * @param name Name of partition dimension
	 * @return True if partition dimension exists
	 */
	public boolean containsPartitionDimension(String name) {
		return getPartitionDimensionOrNull(name) != null;
	}

	private PartitionDimension getPartitionDimensionOrNull(String name) {
		for (PartitionDimension partitionDimension : getPartitionDimensions())
			if (partitionDimension.getName().equals(name))
				return partitionDimension;
		return null;
	}


	/**
	 * Adds a new partition dimension to the hive. The partition dimension
	 * persists to the database along with its NodeGroup, Nodes, Resources, and
	 * SecondaryIndexes. NodeGroup must be defined but the collections of Nodes,
	 * Resources, and SecondaryIndexes may be filled or empty, and modified
	 * later. If the partition dimension has a null indexUri it will be set to
	 * the URI of the hive.
	 * 
	 * @param partitionDimension
	 * @return The PartitionDimension with its Id set and those of all sub
	 *         objects.
	 * @throws HiveException
	 *             Throws if there is any problem persisting the data, if the
	 *             hive is currently read-only, or if a partition dimension of
	 *             the same name already exists
	 */
	public PartitionDimension addPartitionDimension(
			PartitionDimension partitionDimension) throws HiveException {
		throwIfReadOnly("Creating a new partition dimension");
		throwIfNameIsNotUnique(String.format("Partition dimension %s already exists", partitionDimension.getName()), 
				getPartitionDimensions(),
				partitionDimension);

		BasicDataSource datasource = new HiveBasicDataSource(getHiveUri());
		PartitionDimensionDao partitionDimensionDao = new PartitionDimensionDao(datasource);
		
		// We allow the partition dimension to not specify an indexUri and we default it to the hiveUri
		if (partitionDimension.getIndexUri() == null)
			partitionDimension.setIndexUri(this.hiveUri);
		
		try {
			partitionDimensionDao.create(partitionDimension);
		} catch (SQLException e) {
			throw new HiveException(
					"Problem persisting new Partition Dimension: "
							+ e.getMessage());
		}
		this.directories.add(new Directory(partitionDimension, this.dataSource));
		incrementAndPersistHive(datasource);

		return partitionDimension;
	}

	/**
	 * Adds a node to the given partition dimension.
	 * 
	 * @param partitionDimension
	 *            A persisted partition dimension of the hive to which to add
	 *            the node
	 * @param node
	 *            A node instance initialized without an id and without a set
	 *            partition dimension
	 * @return The node with it's id set.
	 * @throws HiveException
	 *             Throws if there is any problem persisting the data, or if the
	 *             hive is currently read-only.
	 */
	public Node addNode(PartitionDimension partitionDimension, Node node)
			throws HiveException {
		node.setNodeGroup(partitionDimension.getNodeGroup());
		
		throwIfReadOnly("Creating a new node");
		throwIfNameIsNotUnique(String.format("Node with URI %s already exists", node.getName()), 
				partitionDimension.getNodeGroup().getNodes(),
				node);
		
		NodeDao nodeDao = new NodeDao(dataSource);
		try {
			nodeDao.create(node);
		} catch (SQLException e) {
			throw new HiveException("Problem persisting new Node: "
					+ e.getMessage());
		}

		incrementAndPersistHive(dataSource);
		sync();
		return node;
	}

	/**
	 * 
	 * Adds a new resource to the given partition dimension, along with any
	 * secondary indexes defined in the resource instance
	 * 
	 * @param partitionDimension
	 *            A persisted partition dimensiono of the hive to which to add
	 *            the resource.
	 * @param resource
	 *            A resource instance initialized without an id and with a full
	 *            or empty collection of secondary indexes.
	 * @return The resource instance with its id set along with those of any
	 *         secondary indexes
	 * @throws HiveException
	 *             Throws if there is any problem persisting the data, if the
	 *             hive is currently read-only, or if a resource of the same
	 *             name already exists
	 */
	public Resource addResource(PartitionDimension partitionDimension,
			Resource resource) throws HiveException {
		resource.setPartitionDimension(partitionDimension);
		throwIfReadOnly("Creating a new resource");
		throwIfNameIsNotUnique(String.format(
				"Resource %s already exists in the partition dimension %s",
				resource.getName(), partitionDimension.getName()),
				partitionDimension.getResources(), resource);

		BasicDataSource datasource = new HiveBasicDataSource(this.getHiveUri());
		ResourceDao resourceDao = new ResourceDao(datasource);
		try {
			resourceDao.create(resource);
		} catch (SQLException e) {
			throw new HiveException("Problem persisting new Resource: "
					+ e.getMessage());
		}
		incrementAndPersistHive(datasource);

		sync();
		return this.getPartitionDimension(partitionDimension.getName()).getResource(resource.getName());
	}
	
	/**
	 * 
	 * Adds a new resource to the given partition dimension, along with any
	 * secondary indexes defined in the resource instance
	 * 
	 * @param dimensionName
	 *            The name of the persisted partition dimension of the hive to which to add
	 *            the resource.
	 * @param resource
	 *            A resource instance initialized without an id and with a full
	 *            or empty collection of secondary indexes.
	 * @return The resource instance with its id set along with those of any
	 *         secondary indexes
	 * @throws HiveException
	 *             Throws if there is any problem persisting the data, if the
	 *             hive is currently read-only, or if a resource of the same
	 *             name already exists
	 */
	public Resource addResource(String dimensionName,
			Resource resource) throws HiveException {
		return addResource(getPartitionDimension(dimensionName), resource);
	}

	/**
	 * 
	 * Adds a partition index to the given resource.
	 * 
	 * @param resource
	 *            A persited resource of a partition dimension of the hive to
	 *            which to add the secondary index
	 * @param secondaryIndex
	 *            A secondary index initialized without an id
	 * @return The SecondaryIndex instance with its id intialized
	 * @throws HiveException
	 *             Throws if there is a problem persisting the data, the hive is
	 *             read-only, or if a secondary index with the same columnInfo()
	 *             name already exists in the resource.
	 */
	public SecondaryIndex addSecondaryIndex(Resource resource,
			SecondaryIndex secondaryIndex) throws HiveException {
		secondaryIndex.setResource(resource);
		throwIfReadOnly("Creating a new secondary index");
		throwIfNameIsNotUnique(String.format(
				"Secondary index %s already exists in the resource %s",
				secondaryIndex.getName(), resource.getName()), resource
				.getSecondaryIndexes(), secondaryIndex);

		BasicDataSource datasource = new HiveBasicDataSource(this.getHiveUri());
		SecondaryIndexDao secondaryIndexDao = new SecondaryIndexDao(datasource);
		try {
			secondaryIndexDao.create(secondaryIndex);
		} catch (SQLException e) {
			throw new HiveException("Problem persisting new SecondaryIndex: "
					+ e.getMessage());
		}
		incrementAndPersistHive(datasource);
		sync();

		try {
			new IndexSchema(getPartitionDimension(resource.getPartitionDimension().getName())).install();
		} catch (SQLException e) {

			throw new HiveException("Problem persisting new SecondaryIndex: "
					+ e.getMessage());
		}
		return secondaryIndex;
	}

	/**
	 * Updates values of a partition dimension in the hive. No updates or adds
	 * to the underlying nodes, resources, or secondary indexes will persist.
	 * You must add or update these objects explicitly before calling this
	 * method. Any data of the partition dimension may be updated except its id.
	 * If new nodes, resources, or secondary indexes have been added to the
	 * partition dimension instance they will be persisted and assigned ids
	 * 
	 * @param partitionDimension
	 *            A partitionDimension persisted in the hive
	 * @return The partitionDimension passed in.
	 * @throws HiveException
	 *             Throws if there is any problem persisting the updates, if the
	 *             hive is currently read-only, or if a partition dimension of
	 *             the same name already exists
	 */
	public PartitionDimension updatePartitionDimension(
			PartitionDimension partitionDimension) throws HiveException {
		throwIfReadOnly("Updating partition dimension");
		throwIfIdNotPresent(String.format(
				"Partition dimension with id %s does not exist",
				partitionDimension.getId()), getPartitionDimensions(),
				partitionDimension);
		throwIfNameIsNotUnique(String.format(
				"Partition dimension with name %s already exists",
				partitionDimension.getName()), getPartitionDimensions(),
				partitionDimension);

		BasicDataSource datasource = new HiveBasicDataSource(getHiveUri());
		PartitionDimensionDao partitionDimensionDao = new PartitionDimensionDao(
				datasource);
		try {
			partitionDimensionDao.update(partitionDimension);
		} catch (SQLException e) {
			throw new HiveException("Problem updating the partition dimension",
					e);
		}
		incrementAndPersistHive(datasource);
		sync();
		
		return partitionDimension;
	}

	/**
	 * Updates the values of a node.
	 * 
	 * @param node
	 *            A node instance initialized without an id and without a set
	 *            partition dimension
	 * @return The node with it's id set.
	 * @throws HiveException
	 *             Throws if there is any problem persisting the data, or if the
	 *             hive is currently read-only.
	 */
	public Node updateNode(Node node) throws HiveException {
		throwIfReadOnly("Updating node");
		throwIfIdNotPresent(String.format("Node with id %s does not exist",
				node.getName()), node.getNodeGroup().getNodes(), node);

		BasicDataSource datasource = new HiveBasicDataSource(this.getHiveUri());
		NodeDao nodeDao = new NodeDao(datasource);
		try {
			nodeDao.update(node);
		} catch (SQLException e) {
			throw new HiveException("Problem updating node: " + e.getMessage());
		}

		incrementAndPersistHive(datasource);

		sync();
		return node;
	}

	/**
	 * 
	 * Updates resource. No secondary index data is created or updated. You
	 * should explicitly create or update any modified secondary index data in
	 * the resource before calling this method.
	 * 
	 * @param resource
	 *            A resource belonging to a partition dimension of the hive
	 * @return The resource instance passed in
	 * @throws HiveException
	 *             Throws if there is any problem persisting the data, if the
	 *             hive is currently read-only, or if a resource of the same
	 *             name already exists
	 */
	public Resource updateResource(Resource resource) throws HiveException {
		throwIfReadOnly("Updating resource");
		throwIfIdNotPresent(String.format(
				"Resource with id %s does not exist", resource.getId()),
				resource.getPartitionDimension().getResources(), resource);
		throwIfNameIsNotUnique(String.format("Resource with name %s already exists", resource
				.getName()), resource.getPartitionDimension().getResources(),
				resource);

		BasicDataSource datasource = new HiveBasicDataSource(this.getHiveUri());
		ResourceDao resourceDao = new ResourceDao(datasource);
		try {
			resourceDao.update(resource);
		} catch (SQLException e) {
			throw new HiveException("Problem persisting resource: "
					+ e.getMessage());
		}
		incrementAndPersistHive(datasource);

		sync();
		return resource;
	}

	/**
	 * 
	 * Adds a partition index to the given resource.
	 * 
	 * @param resource
	 *            A persited resource of a partition dimension of the hive to
	 *            which to add the secondary index
	 * @param secondaryIndex
	 *            A secondary index initialized without an id
	 * @return The SecondaryIndex instance with its id intialized
	 * @throws HiveException
	 *             Throws if there is a problem persisting the data, the hive is
	 *             read-only, or if a secondary index with the same columnInfo()
	 *             name already exists in the resource.
	 */
	public SecondaryIndex updateSecondaryIndex(SecondaryIndex secondaryIndex)
			throws HiveException {
		throwIfReadOnly("Updating secondary index");
		throwIfIdNotPresent(String.format(
				"Secondary index with id %s does not exist", secondaryIndex
						.getId()), secondaryIndex.getResource()
				.getSecondaryIndexes(), secondaryIndex);
		throwIfNameIsNotUnique(String.format("Secondary index with name %s already exists",
				secondaryIndex.getName()), secondaryIndex.getResource()
				.getSecondaryIndexes(), secondaryIndex);

		SecondaryIndexDao secondaryIndexDao = new SecondaryIndexDao(dataSource);
		try {
			secondaryIndexDao.update(secondaryIndex);
		} catch (SQLException e) {
			throw new HiveException("Problem persisting secondary index: "
					+ e.getMessage());
		}
		incrementAndPersistHive(dataSource);

		sync();
		return secondaryIndex;
	}

	/***
	 * Remove a partition dimension from the hive.
	 * @param partitionDimension
	 * @return
	 * @throws HiveException
	 */
	public PartitionDimension deletePartitionDimension(
			PartitionDimension partitionDimension) throws HiveException {
		throwIfReadOnly(String.format("Deleting partition dimension %s",
				partitionDimension.getName()));
		throwUnlessItemExists(
				String
						.format(
								"Partition dimension %s does not match any partition dimension in the hive",
								partitionDimension.getName()),
				getPartitionDimensions(), partitionDimension);
		BasicDataSource datasource = new HiveBasicDataSource(getHiveUri());
		PartitionDimensionDao partitionDimensionDao = new PartitionDimensionDao(datasource);
		try {
			partitionDimensionDao.delete(partitionDimension);
		} catch (SQLException e) {
			throw new HiveException("Problem deletng the partition dimension",
					e);
		}
		incrementAndPersistHive(datasource);
		sync();
		
		//Destroy the corresponding DataSourceCache
		this.jdbcDaoSupportCaches.remove(partitionDimension.getName());
		
		return partitionDimension;
	}

	/***
	 * remove a node from the hive.
	 * @param node
	 * @return
	 * @throws HiveException
	 */
	public Node deleteNode(Node node) throws HiveException {
		throwIfReadOnly(String.format("Deleting node %s", node.getName()));
		throwUnlessItemExists(
				String
						.format(
								"Node %s does not match any node in the partition dimenesion %s",
								node.getName(), node.getNodeGroup()
										.getPartitionDimension().getName()),
				node.getNodeGroup().getPartitionDimension().getNodeGroup()
						.getNodes(), node);
		BasicDataSource datasource = new HiveBasicDataSource(getHiveUri());
		NodeDao nodeDao = new NodeDao(datasource);
		try {
			nodeDao.delete(node);
		} catch (SQLException e) {
			throw new HiveException("Problem deletng the node", e);
		}
		incrementAndPersistHive(datasource);
		sync();
		
		//Synchronize the DataSourceCache
		this.jdbcDaoSupportCaches.get(node.getNodeGroup().getPartitionDimension().getName()).sync();
		return node;
	}

	/***
	 * remove a resource.
	 * @param resource
	 * @return
	 * @throws HiveException
	 */
	public Resource deleteResource(Resource resource) throws HiveException {
		throwIfReadOnly(String.format("Deleting resource %s", resource.getName()));
		throwUnlessItemExists(
				String
						.format(
								"Resource %s does not match any resource in the partition dimenesion %s",
								resource.getName(), resource
										.getPartitionDimension().getName()),
				resource.getPartitionDimension().getResources(), resource);
		BasicDataSource datasource = new HiveBasicDataSource(getHiveUri());
		ResourceDao resourceDao = new ResourceDao(datasource);
		try {
			resourceDao.delete(resource);
		} catch (SQLException e) {
			throw new HiveException("Problem deletng the resource", e);
		}
		incrementAndPersistHive(datasource);
		sync();
		
		return resource;
	}

	/***
	 * Remove a secondary index.
	 * @param secondaryIndex
	 * @return
	 * @throws HiveException
	 */
	public SecondaryIndex deleteSecondaryIndex(SecondaryIndex secondaryIndex)
			throws HiveException {
		throwIfReadOnly(String.format("Deleting secondary index %s", secondaryIndex
				.getName()));
		throwUnlessItemExists(
				String
						.format(
								"Secondary index %s does not match any node in the resource %s",
								secondaryIndex.getName(), secondaryIndex
										.getResource()), secondaryIndex
						.getResource().getSecondaryIndexes(), secondaryIndex);
		BasicDataSource datasource = new HiveBasicDataSource(getHiveUri());
		SecondaryIndexDao secondaryindexDao = new SecondaryIndexDao(datasource);
		try {
			secondaryindexDao.delete(secondaryIndex);
		} catch (SQLException e) {
			throw new HiveException("Problem deletng the secondary index", e);
		}
		incrementAndPersistHive(datasource);
		sync();
		
		return secondaryIndex;
	}

	private void incrementAndPersistHive(DataSource datasource) throws HiveException {
		new HiveSemaphoreDao(datasource).incrementAndPersist();
		this.sync();
	}

	/**
	 * Inserts a new primary index key into the given partition dimension. A
	 * partition dimension by definition defines one primary index. The given
	 * primaryIndexKey must match the column type defined in
	 * partitionDimenion.getColumnInfo().getColumnType(). The node used for the
	 * new primary index key is determined by the hive's
	 * 
	 * @param partitionDimension -
	 *            an existing partition dimension of the hive.
	 * @param primaryIndexKey -
	 *            a primary index key not yet in the primary index.
	 * @throws HiveException
	 *             Throws if the partition dimension is not in the hive, or if
	 *             the hive, primary index or node is currently read only.
	 * @throws SQLException
	 *             Throws if the primary index key already exists, or another
	 *             persitence error occurs.
	 */
	public void insertPrimaryIndexKey(PartitionDimension partitionDimension,
			Object primaryIndexKey) throws HiveException, SQLException {
		// TODO: Consider redesign of NodeGroup to perform assignment, or at
		// least provider direct iteration over Nodes
		Node node = partitionDimension.getAssigner().chooseNode(
				partitionDimension.getNodeGroup().getNodes(), primaryIndexKey);
		throwIfReadOnly("Inserting a new primary index key", node);
		getDirectory(partitionDimension).insertPrimaryIndexKey(node,
				primaryIndexKey);
	}

	/**
	 * Inserts a new primary index key into the given partition dimension. A
	 * partition dimension by definition defines one primary index. The given
	 * primaryIndexKey must match the column type defined in
	 * partitionDimenion.getColumnType().
	 * 
	 * @param partitionDimensionName -
	 *            the name of an existing partition dimension of the hive.
	 * @param primaryIndexKey -
	 *            a primary index key not yet in the primary index.
	 * @throws HiveException
	 *             Throws if the partition dimension is not in the hive, or if
	 *             the hive, primary index or node is currently read only.
	 * @throws SQLException
	 *             Throws if the primary index key already exists, or another
	 *             persitence error occurs.
	 */
	public void insertPrimaryIndexKey(String partitionDimensionName,
			Object primaryIndexKey) throws HiveException, SQLException {
		insertPrimaryIndexKey(getPartitionDimension(partitionDimensionName),
				primaryIndexKey);
	}

	/**
	 * Inserts a new secondary index key and the primary index key which it
	 * references into the given secondary index.
	 * 
	 * @param secondaryIndex
	 *            A secondary index which belongs to the hive via a resource and
	 *            partition dimension
	 * @param secondaryIndexKey
	 *            A secondary index key value whose type must match that defined
	 *            by secondaryIndex.getColumnInfo().getColumnType()
	 * @param primaryindexKey
	 *            A primary index key that already exists in the primary index
	 *            of the partition dimension of this secondary index.
	 * @throws HiveException
	 *             Throws if the secondary index does not exist in the hive via
	 *             a resource and partition dimension, or if the hive is
	 *             currently read-only
	 * @throws SQLException
	 *             Throws if the secondary index key already exists in this
	 *             secondary index, or for any other persistence error.
	 */
	public void insertSecondaryIndexKey(SecondaryIndex secondaryIndex,
			Object secondaryIndexKey, Object primaryindexKey)
			throws HiveException, SQLException {
		
		throwIfReadOnly("Inserting a new secondary index key");
		getDirectory(secondaryIndex.getResource().getPartitionDimension())
				.insertSecondaryIndexKey(secondaryIndex, secondaryIndexKey,
						primaryindexKey);
		partitionStatistics.incrementChildRecordCount(secondaryIndex.getResource()
				.getPartitionDimension(), primaryindexKey, 1);
	}

	/**
	 * 
	 * Inserts a new secondary index key and the primary index key which it
	 * references into the secondary index identified by the give
	 * secondaryIndexName, resourceName, and partitionDimensionName
	 * 
	 * @param partitionDimensionName -
	 *            the name of a partition dimension in the hive
	 * @param resourceName -
	 *            the name of a resource in the partition dimension
	 * @param secondaryIndexName -
	 *            the name of a secondary index in the resource
	 * @param secondaryIndexKey
	 *            A secondary index key value whose type must match that defined
	 *            by secondaryIndex.getColumnInfo().getColumnType()
	 * @param primaryindexKey
	 *            A primary index key that already exists in the primary index
	 *            of the partition dimension of this secondary index.
	 * @throws HiveException
	 *             Throws if the primary index key is not yet in the primary
	 *             index, or if the hive, primary index or node is currently
	 *             read only.
	 * @throws SQLException
	 *             Throws if the secondary index key already exists in this
	 *             secondary index, or for any other persistence error.
	 */
	public void insertSecondaryIndexKey(String partitionDimensionName,
			String resourceName, String secondaryIndexName,
			Object secondaryIndexKey, Object primaryIndexKey)
			throws HiveException, SQLException {
		insertSecondaryIndexKey(getPartitionDimension(partitionDimensionName)
				.getResource(resourceName)
				.getSecondaryIndex(secondaryIndexName), secondaryIndexKey,
				primaryIndexKey);
	}

	/**
	 * 
	 * Updates the node of the given primary index key for the given partition
	 * dimension.
	 * 
	 * @param partitionDimension
	 *            A partition dimension of the hive
	 * @param primaryIndexKey
	 *            An existing primary index key in the primary index
	 * @param node
	 *            A node of the node group of the partition dimension
	 * @throws HiveException
	 *             Throws if the primary index key is not yet in the primary
	 *             index, or if the hive, primary index or node is currently
	 *             read only.
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public void updatePrimaryIndexNode(PartitionDimension partitionDimension,
			Object primaryIndexKey, Node node) throws HiveException {
		getDirectory(partitionDimension).updatePrimaryIndexKey(node,
				primaryIndexKey);
	}

	/**
	 * 
	 * Updates the node of the given primary index key for the given partition
	 * dimension.
	 * 
	 * @param partitionDimensionName
	 *            The name of a partition demension in the hive
	 * @param primaryIndexKey
	 *            An existing primary index key in the primary index
	 * @param nodeUri
	 *            A uri of a node of the node group of the partition dimension
	 * @throws HiveException
	 *             Throws if the primary index key is not yet in the primary
	 *             index, or if the hive, primary index or node is currently
	 *             read only.
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public void updatePrimaryIndexNode(String partitionDimensionName,
			Object primaryIndexKey, String nodeUri) throws HiveException {
		PartitionDimension partitionDimension = getPartitionDimension(partitionDimensionName);
		updatePrimaryIndexNode(partitionDimension, primaryIndexKey,
				partitionDimension.getNodeGroup().getNode(nodeUri));
	}

	/**
	 * 
	 * Updates the read-only status of the given primary index key for the given
	 * partition dimension.
	 * 
	 * @param partitionDimension
	 *            A partition dimension of the hive
	 * @param primaryIndexKey
	 *            An existing primary index key in the primary index
	 * @param isReadOnly
	 *            True makes the primary index key rean-only, false makes it
	 *            writable
	 * @throws HiveException
	 *             Throws if the primary index key is not yet in the primary
	 *             index, or if the hive is currently read only.
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public void updatePrimaryIndexReadOnly(
			PartitionDimension partitionDimension, Object primaryIndexKey,
			boolean isReadOnly) throws HiveException {
		throwIfReadOnly("Updating primary index read-only");
		// This query validates the existence of the primaryIndexKey
		getNodeSemaphoreOfPrimaryIndexKey(partitionDimension, primaryIndexKey);
		getDirectory(partitionDimension).updatePrimaryIndexKeyReadOnly(
				primaryIndexKey, isReadOnly);
	}

	/**
	 * 
	 * Updates the read-only status of the given primary index key for the given
	 * partition dimension.
	 * 
	 * @param partitionDimensionName
	 *            The name of a partition dimension of the hive
	 * @param primaryIndexKey
	 *            An existing primary index key in the primary index
	 * @param isReadOnly
	 *            True makes the primary index key rean-only, false makes it
	 *            writable
	 * @throws HiveException
	 *             Throws if the primary index key is not yet in the primary
	 *             index, or if the hive is currently read only.
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public void updatePrimaryIndexReadOnly(String partitionDimensionName,
			Object primaryIndexKey, boolean isReadOnly) throws HiveException {
		PartitionDimension partitionDimension = getPartitionDimension(partitionDimensionName);
		updatePrimaryIndexReadOnly(partitionDimension, primaryIndexKey,
				isReadOnly);
	}

	/**
	 * 
	 * Updates the primary index key of the given secondary index key.
	 * 
	 * @param secondaryIndex
	 *            A secondary index that belongs to the hive via a resource and
	 *            partition dimension
	 * @param secondaryIndexKey
	 *            A secondary index key of the given secondary index
	 * @param primaryIndexKey
	 *            The primary index key to assign to the secondary index key
	 * @throws HiveException
	 *             Throws if the secondary index key is not yet in the secondary
	 *             index, or if the hive is currently read only.
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public void updatePrimaryIndexKeyOfSecondaryIndexKey(
			SecondaryIndex secondaryIndex, Object secondaryIndexKey,
			Object primaryIndexKey) throws HiveException, SQLException {
		
		throwIfReadOnly("Updating primary index key of secondary index key");
		getPrimaryIndexKeyOfSecondaryIndexKey(secondaryIndex, secondaryIndexKey);
		getDirectory(secondaryIndex.getResource().getPartitionDimension())
				.updatePrimaryIndexOfSecondaryKey(secondaryIndex,
						secondaryIndexKey, primaryIndexKey);
	}

	/**
	 * 
	 * @param partitionDimensionName
	 *            The name of a partition dimension in this hive
	 * @param resourceName
	 *            The name of a resource in the given partition dimension
	 * @param secondaryIndexName
	 *            The name of a secondary index of the given resource
	 * @param secondaryIndexKey
	 *            A secondary index key of the given secondary index
	 * @param primaryIndexKey
	 *            The primary index key to assign to the secondary index key
	 * @throws HiveException
	 *             Throws if the secondary index key is not yet in the secondary
	 *             index, or if the hive is currently read only.
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public void updatePrimaryIndexKeyOfSecondaryIndexKey(
			String partitionDimensionName, String resourceName,
			String secondaryIndexName, Object secondaryIndexKey,
			Object primaryIndexKey) throws HiveException, SQLException {
		updatePrimaryIndexKeyOfSecondaryIndexKey(getPartitionDimension(
				partitionDimensionName).getResource(resourceName)
				.getSecondaryIndex(secondaryIndexName), secondaryIndexKey,
				primaryIndexKey);
	}

	/**
	 * Deletes the primary index key of the given partition dimension
	 * 
	 * @param partitionDimension
	 *            A partition dimension in the hive
	 * @param primaryIndexKey
	 *            An existing primary index key of the partition dimension
	 * @throws HiveException
	 *             Throws if the primary index key does not exist or if the
	 *             hive, node of the primary index key, or primary index key
	 *             itself is currently read-only
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public void deletePrimaryIndexKey(PartitionDimension partitionDimension,
			Object primaryIndexKey) throws HiveException, SQLException {

		if (!doesPrimaryIndexKeyExist(partitionDimension, primaryIndexKey))
			throw new HiveException("The primary index key " + primaryIndexKey
					+ " does not exist");
		throwIfReadOnly("Deleting primary index key", partitionDimension.getNodeGroup().getNode(getNodeSemaphoreOfPrimaryIndexKey(
				partitionDimension, primaryIndexKey).getId()), primaryIndexKey,
				getReadOnlyOfPrimaryIndexKey(partitionDimension,
						primaryIndexKey));

		for (Resource resource : partitionDimension.getResources())
			for (SecondaryIndex secondaryIndex : resource.getSecondaryIndexes()) {
				getDirectory(partitionDimension)
						.deleteAllSecondaryIndexKeysOfPrimaryIndexKey(
								secondaryIndex, primaryIndexKey);
			}

		getDirectory(partitionDimension).deletePrimaryIndexKey(primaryIndexKey);
	}

	/**
	 * Deletes a primary index key of the given partition dimension
	 * 
	 * @param partitionDimensionName
	 *            The name of a partition dimension in the hive
	 * @param primaryIndexKey
	 *            An existing primary index key of the partition dimension
	 * @throws HiveException
	 *             Throws if the primary index key does not exist or if the
	 *             hive, node of the primary index key, or primary index key
	 *             itself is currently read-only
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public void deletePrimaryIndexKey(String partitionDimensionName,
			Object secondaryIndexKey) throws HiveException, SQLException {
		deletePrimaryIndexKey(getPartitionDimension(partitionDimensionName),
				secondaryIndexKey);
	}

	/**
	 * Deletes a secondary index key of the give secondary index
	 * 
	 * @param secondaryIndex
	 *            A secondary index that belongs to the hive via its resource
	 *            and partition dimension
	 * @param secondaryIndexKey
	 *            An existing secondary index key
	 * @throws HiveException
	 *             Throws if the secondary index key does not exist or if the
	 *             hive is currently read-only
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public void deleteSecondaryIndexKey(SecondaryIndex secondaryIndex,
			Object secondaryIndexKey) throws HiveException, SQLException {
		Object primaryIndexKey = getPrimaryIndexKeyOfSecondaryIndexKey(
				secondaryIndex, secondaryIndexKey);
		throwIfReadOnly("Deleting secondary index key");

		if (!doesSecondaryIndexKeyExist(secondaryIndex, secondaryIndexKey))
			throw new HiveException("Secondary index key "
					+ secondaryIndexKey.toString() + " does not exist");

		getDirectory(secondaryIndex.getResource().getPartitionDimension())
				.deleteSecondaryIndexKey(secondaryIndex, secondaryIndexKey);
		partitionStatistics.decrementChildRecordCount(secondaryIndex.getResource()
				.getPartitionDimension(), primaryIndexKey, 1);
	}

	/**
	 * Deletes a secondary index key of the give secondary index
	 * 
	 * @param partitionDimensionName
	 *            The name of a partition dimension in the hive
	 * @param resourceName
	 *            The name of a resource in the partition dimension
	 * @param secondaryIndexName
	 *            The name of a secondary index of the resource
	 * @param secondaryIndex
	 *            A secondary index that belongs to the hive via its resource
	 *            and partition dimension
	 * @param secondaryIndexKey
	 *            An existing secondary index key
	 * @throws HiveException
	 *             Throws if the secondary index key does not exist or if the
	 *             hive is currently read-only
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public void deleteSecondaryIndexKey(String partitionDimensionName,
			String resourceName, String secondaryIndexName,
			Object secondaryIndexKey) throws HiveException, SQLException {
		deleteSecondaryIndexKey(getPartitionDimension(partitionDimensionName)
				.getResource(resourceName)
				.getSecondaryIndex(secondaryIndexName), secondaryIndexKey);
	}

	/**
	 * Returns true if the primary index key exists in the given partition
	 * dimension
	 * 
	 * @param partitionDimension
	 *            A partition dimension in the hive
	 * @param primaryIndexKey
	 *            The key to test
	 * @return
	 * @throws HiveException
	 *             Throws if the partition dimension is not in the hive
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public boolean doesPrimaryIndexKeyExist(
			PartitionDimension partitionDimension, Object primaryIndexKey)
			throws HiveException, SQLException {
		return getDirectory(partitionDimension).doesPrimaryIndexKeyExist(
				primaryIndexKey);
	}

	/**
	 * Returns true if the primary index key exists in the given partition
	 * dimension
	 * 
	 * @param partitionDimensionName
	 *            The name of a partition dimension in the hive
	 * @param primaryIndexKey
	 *            The key to test
	 * @return
	 * @throws HiveException
	 *             Throws if the partition dimension is not in the hive
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public boolean doesPrimaryIndeyKeyExist(String partitionDimensionName,
			Object primaryIndexKey) throws HiveException, SQLException {
		return doesPrimaryIndexKeyExist(
				getPartitionDimension(partitionDimensionName), primaryIndexKey);
	}

	/**
	 * Returns the node assigned to the given primary index key
	 * 
	 * @param partitionDimensionName
	 *            The name of a partition dimension in the hive
	 * @param primaryIndexKey
	 *            A primary index key belonging to the partition dimension
	 * @return
	 * @throws HiveException
	 *             Throws if the partition dimension or primary index key does
	 *             not exist
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	private NodeSemaphore getNodeSemaphoreOfPrimaryIndexKey(PartitionDimension partitionDimension,
			Object primaryIndexKey) throws HiveException {
		try {
			return getDirectory(partitionDimension).getNodeSemamphoreOfPrimaryIndexKey(primaryIndexKey);
		} catch (Exception e) {
			throw new HiveException(
					String
							.format(
									"Primary index key %s of partition dimension %s not found.",
									primaryIndexKey.toString(),
									partitionDimension.getName()), e);
		}
	}
	
	/**
	 * Returns true is the given primary index key is read-only
	 * 
	 * @param partitionDimension
	 *            A partition dimension in the hive
	 * @param primaryIndexKey
	 *            An existing primary index key of the partition dimension
	 * @return
	 * @throws HiveException
	 *             Throws if the primary index key does not exist
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public boolean getReadOnlyOfPrimaryIndexKey(
			PartitionDimension partitionDimension, Object primaryIndexKey)
			throws HiveException {
		Boolean readOnly = getDirectory(partitionDimension)
				.getReadOnlyOfPrimaryIndexKey(primaryIndexKey);
		if (readOnly != null)
			return readOnly;
		throw new HiveException(String.format(
				"Primary index key %s of partition dimension %s not found.",
				primaryIndexKey.toString(), partitionDimension.getName()));
	}

	/**
	 * Returns true is the given primary index key is read-only
	 * 
	 * @param partitionDimensionName
	 *            The name of a partition dimension in the hive
	 * @param primaryIndexKey
	 *            An existing primary index key of the partition dimension
	 * @return
	 * @throws HiveException
	 *             Throws if the primary index key does not exist
	 * @throws SQLException
	 *             Throws if there is a persistence error
	 */
	public boolean getReadOnlyOfPrimaryIndexKey(String partitionDimensionName,
			Object primaryIndexKey) throws HiveException {
		return getReadOnlyOfPrimaryIndexKey(
				getPartitionDimension(partitionDimensionName), primaryIndexKey);
	}

	/**
	 * Tests the existence of a give secondary index key
	 * 
	 * @param secondaryIndex
	 *            A secondary index that belongs to the hive via its resource
	 *            and partition index
	 * @param secondaryIndexKey
	 *            The key to test
	 * @return True if the secondary index key exists
	 * @throws SQLException
	 *             Throws an exception if there is a persistence error
	 */
	public boolean doesSecondaryIndexKeyExist(SecondaryIndex secondaryIndex,
			Object secondaryIndexKey) {
		return getDirectory(
				secondaryIndex.getResource().getPartitionDimension())
				.doesSecondaryIndexKeyExist(secondaryIndex, secondaryIndexKey);
	}

	/**
	 * 
	 * Tests the existence of a give secondary index key
	 * 
	 * @param partitionDimensionName
	 *            The name of a partition dimension in the hive
	 * @param resourceName
	 *            The name of a resource in the partition dimesnion
	 * @param secondaryIndexName
	 *            The name of a secondary index of the resource
	 * @param secondaryIndexKey
	 *            The key of the secondary index to test
	 * @return True if the key exists in the secondary index
	 * @throws HiveException
	 *             Throws if the partition dimension, resource, or secondary
	 *             index does not exist
	 */
	public boolean doesSecondaryIndexKeyExist(String partitionDimensionName,
			String resourceName, String secondaryIndexName,
			Object secondaryIndexKey) throws HiveException {
		return doesSecondaryIndexKeyExist(getPartitionDimension(
				partitionDimensionName).getResource(resourceName)
				.getSecondaryIndex(secondaryIndexName), secondaryIndexKey);
	}
	
	private NodeSemaphore getNodeSemaphoreOfSecondaryIndexKey(SecondaryIndex secondaryIndex,
			Object secondaryIndexKey) throws HiveException {
		PartitionDimension partitionDimension = secondaryIndex.getResource()
				.getPartitionDimension();
		try {
			return getDirectory(partitionDimension).getNodeSemaphoreOfSecondaryIndexKey(secondaryIndex, secondaryIndexKey);
		} catch (Exception e) {
			throw new HiveException(
					String
							.format(
									"Secondary index key %s of partition dimension %s on secondary index %s not found.",
									secondaryIndexKey,
									partitionDimension.getName(),
									secondaryIndex.getName()), e);
		}
	}

	/**
	 * Returns the primary index key of the given secondary index key
	 * 
	 * @param secondaryIndex
	 *            A secondary index that belongs to the hive via its resource
	 *            and partition dimension
	 * @param secondaryIndexKey
	 *            The secondary in
	 * @return
	 * @throws HiveException
	 */
	public Object getPrimaryIndexKeyOfSecondaryIndexKey(
			SecondaryIndex secondaryIndex, Object secondaryIndexKey)
			throws HiveException {
		PartitionDimension partitionDimension = secondaryIndex.getResource()
				.getPartitionDimension();
		Object primaryIndexKey = getDirectory(partitionDimension)
				.getPrimaryIndexKeyOfSecondaryIndexKey(secondaryIndex,
						secondaryIndexKey);
		if (primaryIndexKey != null)
			return primaryIndexKey;
		throw new HiveException(
				String
						.format(
								"Secondary index key %s of partition dimension %s on secondary index %s not found.",
								secondaryIndex.toString(), partitionDimension
										.getName(), secondaryIndex.getName()));
	}

	/**
	 * Returns all secondary index keys pertaining to the given primary index
	 * key. The primary index key may or may not exist in the primary index and
	 * there may be zero or more keys returned.
	 * 
	 * @param secondaryIndex
	 *            the secondary index to query
	 * @param primaryIndexKey
	 *            the primary index key with which to query
	 * @return
	 */
	public Collection getSecondaryIndexKeysWithPrimaryKey(
			SecondaryIndex secondaryIndex, Object primaryIndexKey) {
		return getDirectory(
				secondaryIndex.getResource().getPartitionDimension())
				.getSecondaryIndexKeysOfPrimaryIndexKey(secondaryIndex,
						primaryIndexKey);
	}

	/**
	 * 
	 * Returns all secondary index keys pertaining to the given primary index
	 * key. The primary index key must exist in the primary index and there may
	 * be zero or more keys returned.
	 * 
	 * @param partitionDimensionName
	 * @param resource
	 * @param secondaryIndexName
	 * @param primaryIndexKey
	 * @return
	 * @throws HiveException
	 */
	public Collection getSecondaryIndexKeysWithPrimaryKey(
			String partitionDimensionName, String resource,
			String secondaryIndexName, Object primaryIndexKey)
			throws HiveException {
		return getSecondaryIndexKeysWithPrimaryKey(getPartitionDimension(
				partitionDimensionName).getResource(resource)
				.getSecondaryIndex(secondaryIndexName), primaryIndexKey);
	}
	
	private Connection getConnection(PartitionDimension partitionDimension, NodeSemaphore semaphore, AccessType intention) throws SQLException, HiveException {
		try{
			if( intention == AccessType.ReadWrite && isKeyReadOnly(partitionDimension, semaphore))
				throw new HiveReadOnlyException("The key/node/hive requested cannot be written to at this time.");
			
			Connection conn = DriverManager.getConnection(partitionDimension.getNodeGroup().getNode(semaphore.getId()).getUri());
			if(intention == AccessType.Read) {
				conn.setReadOnly(true);
				if( isPerformanceMonitoringEnabled() )
					performanceStatistics.incrementNewReadConnections();
			} else if( intention == AccessType.ReadWrite){
				if( isPerformanceMonitoringEnabled() )
					performanceStatistics.incrementNewWriteConnections();
			}
			return conn;
		} catch (SQLException e) {
			if( isPerformanceMonitoringEnabled() )
				performanceStatistics.incrementConnectionFailures();
			throw e;
		} catch( RuntimeException e) {
			if( isPerformanceMonitoringEnabled() )
				performanceStatistics.incrementConnectionFailures();
			throw e;
		} catch (HiveException e) {
			if( isPerformanceMonitoringEnabled() )
				performanceStatistics.incrementConnectionFailures();
			throw e;
		}
	}
	
	private boolean isKeyReadOnly(PartitionDimension partitionDimension, NodeSemaphore semaphore) throws HiveException {
		return isReadOnly() || partitionDimension.getNodeGroup().getNode(semaphore.getId()).isReadOnly() || semaphore.isReadOnly();
	}
	
	/***
	 * Get a JDBC connection to a data node by partition key.
	 * @param partitionDimension
	 * @param primaryIndexKey
	 * @param intent
	 * @return
	 * @throws HiveException
	 * @throws SQLException 
	 */
	public Connection getConnection(PartitionDimension partitionDimension,
			Object primaryIndexKey, AccessType intent) throws HiveException, SQLException {
		return getConnection(partitionDimension, getNodeSemaphoreOfPrimaryIndexKey(partitionDimension, primaryIndexKey), intent);
	}
	
	/***
	 * Get a JDBC connection to a data node by a secondary index key.
	 * @param secondaryIndex
	 * @param secondaryIndexKey
	 * @param intent
	 * @return
	 * @throws HiveException
	 * @throws SQLException
	 */
	public Connection getConnection(SecondaryIndex secondaryIndex,
			Object secondaryIndexKey, AccessType intent) throws HiveException, SQLException {
		return getConnection(
				secondaryIndex.getResource().getPartitionDimension(), 
				getNodeSemaphoreOfSecondaryIndexKey(secondaryIndex, secondaryIndexKey), 
				intent);
	}
	
	/***
	 * Get a JdbcDaoSupportCache for a partition dimension.
	 * @param partitionDimension
	 * @return
	 */
	public JdbcDaoSupportCache getJdbcDaoSupportCache(PartitionDimension partitionDimension) {
		return this.getJdbcDaoSupportCache(partitionDimension.getName());
	}
	/***
	 * Get a JdbcDaoSupportCache for a partition dimension by name.
	 * @param partitionDimension
	 * @return
	 */
	public JdbcDaoSupportCache getJdbcDaoSupportCache(String partitionDimensionName) {
		return this.jdbcDaoSupportCaches.get(partitionDimensionName);
	}

	public String toString() {
		return HiveUtils.toDeepFormatedString(this, "HiveUri", getHiveUri(),
				"Revision", getRevision(), "PartitionDimensions",
				getPartitionDimensions());
	}

	@SuppressWarnings("unchecked")
	private <T extends IdAndNameIdentifiable> void throwIfNameIsNotUnique(
			String errorMessage, Collection<T> collection, T item)
			throws HiveException {
		// Forbids duplicate names for two different instances if the class
		// implements Identifies
		if(!IdentifiableUtils.isNameUnique((Collection<IdAndNameIdentifiable>) collection, item))
				throw new HiveException(errorMessage);
	}

	@SuppressWarnings("unchecked")
	private <T extends Identifiable> void throwIfIdNotPresent(
			String errorMessage, Collection<T> collection, T item)
			throws HiveException {
		if(!IdentifiableUtils.isIdPresent((Collection<IdAndNameIdentifiable>)collection, (IdAndNameIdentifiable) item))
			throw new HiveException(errorMessage);
	}

	private <T> void throwUnlessItemExists(String errorMessage,
			Collection<T> collection, T item) throws HiveException {
		// All classes implement Comparable, so this does a deep compare on all
		// objects owned by the item.
		if (!collection.contains(item))
			throw new HiveException(errorMessage);
	}

	private void throwIfReadOnly(String errorMessage) throws HiveException {
		if (this.isReadOnly())
			throw new HiveReadOnlyException(
					errorMessage
							+ ". This operation is invalid because the hive is currently read-only.");
	}

	private void throwIfReadOnly(String errorMessage, Node node)
			throws HiveException {
		throwIfReadOnly(errorMessage);
		if (node.isReadOnly())
			throw new HiveReadOnlyException(errorMessage
					+ ". This operation is invalid becuase the selected node "
					+ node.getId() + " is currently read-only.");
	}

	private void throwIfReadOnly(String errorMessage, Node node,
			Object primaryIndexKeyId, boolean primaryIndexKeyReadOnly)
			throws HiveException {
		throwIfReadOnly(errorMessage, node);
		if (primaryIndexKeyReadOnly)
			throw new HiveReadOnlyException(
					errorMessage
							+ ". This operation is invalid becuase the primary index key "
							+ primaryIndexKeyId.toString()
							+ " is currently read-only.");
	}

	private Directory getDirectory(PartitionDimension dimension) {
		for (Directory directory : directories)
			if (dimension.getName().equals(
					directory.getPartitionDimension().getName()))
				return directory;

		String msg = "Could not find directory for partition dimension "
				+ dimension.getName();
		throw new NoSuchElementException(msg);
	}

	public PartitionKeyStatisticsDao getPartitionStatistics() {
		return partitionStatistics;
	}

	public HivePerformanceStatistics getPerformanceStatistics() {
		return performanceStatistics;
	}

	public void setPerformanceStatistics(HivePerformanceStatistics performanceStatistics) {
		this.performanceStatistics = performanceStatistics;
	}

	public boolean isPerformanceMonitoringEnabled() {
		return performanceStatistics != null && performanceMonitoringEnabled;
	}

	public void setPerformanceMonitoringEnabled(boolean performanceMonitoringEnabled) {
		this.performanceMonitoringEnabled = performanceMonitoringEnabled;
	}

}