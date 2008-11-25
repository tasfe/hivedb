package org.hivedb;

import org.hivedb.Hive;

public class ResourceIndex extends SecondaryIndexImpl {

  public ResourceIndex(SecondaryIndexImpl index) {
    this(index.getId(), index.getName(), index.getColumnInfo().getColumnType());
    if(index.getResource() != null)
      setResource(index.getResource());
  }

  public ResourceIndex(String name, int type, Resource resource) {
    this(name, type);
    setResource(resource);
  }

  public ResourceIndex(String name, int type) {
		this(Hive.NEW_OBJECT_ID,name,type);
	}
	
	public ResourceIndex(int id, String name, int type) {
		super(id, name, type);
	}

	@Override
	public String getTableName() { return getTableName(this.getName(), "id");}
}
