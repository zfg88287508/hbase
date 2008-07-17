/**
 * Copyright 2007 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.WritableComparable;

/**
 * HTableDescriptor contains the name of an HTable, and its
 * column families.
 */
public class HTableDescriptor implements WritableComparable {
  /** Table descriptor for <core>-ROOT-</code> catalog table */
  public static final HTableDescriptor ROOT_TABLEDESC = new HTableDescriptor(
      HConstants.ROOT_TABLE_NAME,
      new HColumnDescriptor[] { new HColumnDescriptor(HConstants.COLUMN_FAMILY,
          1, HColumnDescriptor.CompressionType.NONE, false, false,
          Integer.MAX_VALUE, HConstants.FOREVER, false) });
  
  /** Table descriptor for <code>.META.</code> catalog table */
  public static final HTableDescriptor META_TABLEDESC = new HTableDescriptor(
      HConstants.META_TABLE_NAME, new HColumnDescriptor[] {
          new HColumnDescriptor(HConstants.COLUMN_FAMILY, 1,
              HColumnDescriptor.CompressionType.NONE, false, false,
              Integer.MAX_VALUE, HConstants.FOREVER, false),
          new HColumnDescriptor(HConstants.COLUMN_FAMILY_HISTORIAN,
              HConstants.ALL_VERSIONS, HColumnDescriptor.CompressionType.NONE,
              false, false, Integer.MAX_VALUE, HConstants.FOREVER, false) });

  // Changes prior to version 3 were not recorded here.
  // Version 3 adds metadata as a map where keys and values are byte[].
  public static final byte TABLE_DESCRIPTOR_VERSION = 3;

  private byte [] name = HConstants.EMPTY_BYTE_ARRAY;
  private String nameAsString = "";

  // Table metadata
  protected Map<ImmutableBytesWritable,ImmutableBytesWritable> values =
    new HashMap<ImmutableBytesWritable,ImmutableBytesWritable>();
  
  public static final String FAMILIES = "FAMILIES";

  public static final String MAX_FILESIZE = "MAX_FILESIZE";
  public static final String IN_MEMORY = "IN_MEMORY";
  public static final String READONLY = "READONLY";
  public static final String MEMCACHE_FLUSHSIZE = "MEMCACHE_FLUSHSIZE";
  public static final String IS_ROOT = "IS_ROOT";
  public static final String IS_META = "IS_META";

  public static final boolean DEFAULT_IN_MEMORY = false;

  public static final boolean DEFAULT_READONLY = false;

  public static final int DEFAULT_MEMCACHE_FLUSH_SIZE = 1024*1024*64;
  
  // Key is hash of the family name.
  private final Map<Integer, HColumnDescriptor> families =
    new HashMap<Integer, HColumnDescriptor>();

  /**
   * Private constructor used internally creating table descriptors for 
   * catalog tables: e.g. .META. and -ROOT-.
   */
  private HTableDescriptor(final byte [] name, HColumnDescriptor[] families) {
    this.name = name.clone();
    setMetaFlags(name);
    for(HColumnDescriptor descriptor : families) {
      this.families.put(Bytes.mapKey(descriptor.getName()), descriptor);
    }
  }

  /**
   * Constructs an empty object.
   * For deserializing an HTableDescriptor instance only.
   * @see #HTableDescriptor(byte[])
   */
  public HTableDescriptor() {
    super();
  }

  /**
   * Constructor.
   * @param name Table name.
   * @throws IllegalArgumentException if passed a table name
   * that is made of other than 'word' characters, underscore or period: i.e.
   * <code>[a-zA-Z_0-9.].
   * @see <a href="HADOOP-1581">HADOOP-1581 HBASE: Un-openable tablename bug</a>
   */
  public HTableDescriptor(final String name) {
    this(Bytes.toBytes(name));
  }

  /**
   * Constructor.
   * @param name Table name.
   * @throws IllegalArgumentException if passed a table name
   * that is made of other than 'word' characters, underscore or period: i.e.
   * <code>[a-zA-Z_0-9.].
   * @see <a href="HADOOP-1581">HADOOP-1581 HBASE: Un-openable tablename bug</a>
   */
  public HTableDescriptor(final byte [] name) {
    super();
    this.name = this.isMetaRegion() ? name: isLegalTableName(name);
    this.nameAsString = Bytes.toString(this.name);
    setMetaFlags(this.name);
  }

  /**
   * Constructor.
   * <p>
   * Makes a deep copy of the supplied descriptor. 
   * Can make a modifiable descriptor from an UnmodifyableHTableDescriptor.
   * @param desc The descriptor.
   */
  public HTableDescriptor(final HTableDescriptor desc)
  {
    super();
    this.name = desc.name.clone();
    this.nameAsString = Bytes.toString(this.name);
    setMetaFlags(this.name);
    for (HColumnDescriptor c: desc.families.values()) {
      this.families.put(Bytes.mapKey(c.getName()), new HColumnDescriptor(c));
    }
    for (Map.Entry<ImmutableBytesWritable, ImmutableBytesWritable> e:
        desc.values.entrySet()) {
      this.values.put(e.getKey(), e.getValue());
    }
  }

  /*
   * Set meta flags on this table.
   * Called by constructors.
   * @param name
   */
  private void setMetaFlags(final byte [] name) {
    setRootRegion(Bytes.equals(name, HConstants.ROOT_TABLE_NAME));
    setMetaRegion(isRootRegion() ||
      Bytes.equals(name, HConstants.META_TABLE_NAME));
  }

  /** @return true if this is the root region */
  public boolean isRootRegion() {
    String value = getValue(IS_ROOT);
    if (value != null)
      return Boolean.valueOf(value);
    return false;
  }

  /** @param isRoot true if this is the root region */
  protected void setRootRegion(boolean isRoot) {
    values.put(new ImmutableBytesWritable(Bytes.toBytes(IS_ROOT)),
      new ImmutableBytesWritable(Bytes.toBytes(Boolean.toString(isRoot))));
  }

  /** @return true if this is a meta region (part of the root or meta tables) */
  public boolean isMetaRegion() {
    String value = getValue(IS_META);
    if (value != null)
      return Boolean.valueOf(value);
    return false;
  }

  /**
   * @param isMeta true if this is a meta region (part of the root or meta
   * tables) */
  protected void setMetaRegion(boolean isMeta) {
    values.put(new ImmutableBytesWritable(Bytes.toBytes(IS_META)),
      new ImmutableBytesWritable(Bytes.toBytes(Boolean.toString(isMeta))));
  }

  /** @return true if table is the meta table */
  public boolean isMetaTable() {
    return isMetaRegion() && !isRootRegion();
  }

  /**
   * Check passed buffer is legal user-space table name.
   * @param b Table name.
   * @return Returns passed <code>b</code> param
   * @throws NullPointerException If passed <code>b</code> is null
   * @throws IllegalArgumentException if passed a table name
   * that is made of other than 'word' characters or underscores: i.e.
   * <code>[a-zA-Z_0-9].
   */
  public static byte [] isLegalTableName(final byte [] b) {
    if (b == null || b.length <= 0) {
      throw new IllegalArgumentException("Name is null or empty");
    }
    for (int i = 0; i < b.length; i++) {
      if (Character.isLetterOrDigit(b[i]) || b[i] == '_') {
        continue;
      }
      throw new IllegalArgumentException("Illegal character <" + b[i] + ">. " +
        "User-space table names can only contain 'word characters':" +
        "i.e. [a-zA-Z_0-9]: " + Bytes.toString(b));
    }
    return b;
  }

  /**
   * @param key The key.
   * @return The value.
   */
  public byte[] getValue(byte[] key) {
    ImmutableBytesWritable ibw = values.get(new ImmutableBytesWritable(key));
    if (ibw == null)
      return null;
    return ibw.get();
  }

  /**
   * @param key The key.
   * @return The value as a string.
   */
  public String getValue(String key) {
    byte[] value = getValue(Bytes.toBytes(key));
    if (value == null)
      return null;
    return Bytes.toString(value);
  }

  /**
   * @param key The key.
   * @param value The value.
   */
  public void setValue(byte[] key, byte[] value) {
    values.put(new ImmutableBytesWritable(key),
      new ImmutableBytesWritable(value));
  }

  /**
   * @param key The key.
   * @param value The value.
   */
  public void setValue(String key, String value) {
    setValue(Bytes.toBytes(key), Bytes.toBytes(value));
  }

  /**
   * @return true if all columns in the table should be kept in the 
   * HRegionServer cache only
   */
  public boolean isInMemory() {
    String value = getValue(IN_MEMORY);
    if (value != null)
      return Boolean.valueOf(value);
    return DEFAULT_IN_MEMORY;
  }

  /**
   * @param inMemory True if all of the columns in the table should be kept in
   * the HRegionServer cache only.
   */
  public void setInMemory(boolean inMemory) {
    setValue(IN_MEMORY, Boolean.toString(inMemory));
  }

  /**
   * @return true if all columns in the table should be read only
   */
  public boolean isReadOnly() {
    String value = getValue(READONLY);
    if (value != null)
      return Boolean.valueOf(value);
    return DEFAULT_READONLY;
  }

  /**
   * @param readOnly True if all of the columns in the table should be read
   * only.
   */
  public void setReadOnly(boolean readOnly) {
    setValue(READONLY, Boolean.toString(readOnly));
  }

  /** @return name of table */
  public byte [] getName() {
    return name;
  }

  /** @return name of table */
  public String getNameAsString() {
    return this.nameAsString;
  }

  /** @return max hregion size for table */
  public long getMaxFileSize() {
    String value = getValue(MAX_FILESIZE);
    if (value != null)
      return Long.valueOf(value);
    return HConstants.DEFAULT_MAX_FILE_SIZE;
  }

  /**
   * @param maxFileSize The maximum file size that a store file can grow to
   * before a split is triggered.
   */
  public void setMaxFileSize(long maxFileSize) {
    setValue(MAX_FILESIZE, Long.toString(maxFileSize));
  }

  /**
   * @return memory cache flush size for each hregion
   */
  public int getMemcacheFlushSize() {
    String value = getValue(MEMCACHE_FLUSHSIZE);
    if (value != null)
      return Integer.valueOf(value);
    return DEFAULT_MEMCACHE_FLUSH_SIZE;
  }

  /**
   * @param memcacheFlushSize memory cache flush size for each hregion
   */
  public void setMemcacheFlushSize(int memcacheFlushSize) {
    setValue(MEMCACHE_FLUSHSIZE, Integer.toString(memcacheFlushSize));
  }

  /**
   * Adds a column family.
   * @param family HColumnDescriptor of familyto add.
   */
  public void addFamily(final HColumnDescriptor family) {
    if (family.getName() == null || family.getName().length <= 0) {
      throw new NullPointerException("Family name cannot be null or empty");
    }
    this.families.put(Bytes.mapKey(family.getName()), family);
  }

  /**
   * Checks to see if this table contains the given column family
   * @param c Family name or column name.
   * @return true if the table contains the specified family name
   */
  public boolean hasFamily(final byte [] c) {
    return hasFamily(c, HStoreKey.getFamilyDelimiterIndex(c));
  }

  /**
   * Checks to see if this table contains the given column family
   * @param c Family name or column name.
   * @param index Index to column family delimiter
   * @return true if the table contains the specified family name
   */
  public boolean hasFamily(final byte [] c, final int index) {
    // If index is -1, then presume we were passed a column family name minus
    // the colon delimiter.
    return families.containsKey(Bytes.mapKey(c, index == -1? c.length: index));
  }

  /**
   * @return Name of this table and then a map of all of the column family
   * descriptors.
   * @see #getNameAsString()
   */
  @Override
  public String toString() {
    StringBuffer s = new StringBuffer();
    s.append('{');
    s.append(HConstants.NAME);
    s.append(" => '");
    s.append(Bytes.toString(name));
    s.append("'");
    for (Map.Entry<ImmutableBytesWritable, ImmutableBytesWritable> e:
        values.entrySet()) {
      s.append(", ");
      s.append(Bytes.toString(e.getKey().get()));
      s.append(" => '");
      s.append(Bytes.toString(e.getValue().get()));
      s.append("'");
    }
    s.append(", ");
    s.append(FAMILIES);
    s.append(" => ");
    s.append(families.values());
    s.append('}');
    return s.toString();
  }

  /** {@inheritDoc} */
  @Override
  public boolean equals(Object obj) {
    return compareTo(obj) == 0;
  }
  
  /** {@inheritDoc} */
  @Override
  public int hashCode() {
    int result = Bytes.hashCode(this.name);
    result ^= Byte.valueOf(TABLE_DESCRIPTOR_VERSION).hashCode();
    if (this.families != null && this.families.size() > 0) {
      for (HColumnDescriptor e: this.families.values()) {
        result ^= e.hashCode();
      }
    }
    result ^= values.hashCode();
    return result;
  }

  // Writable

  /** {@inheritDoc} */
  public void readFields(DataInput in) throws IOException {
    int version = in.readInt();
    if (version < 3)
      throw new IOException("versions < 3 are not supported (and never existed!?)");
    // version 3+
    name = Bytes.readByteArray(in);
    nameAsString = Bytes.toString(this.name);
    setRootRegion(in.readBoolean());
    setMetaRegion(in.readBoolean());
    values.clear();
    int numVals = in.readInt();
    for (int i = 0; i < numVals; i++) {
      ImmutableBytesWritable key = new ImmutableBytesWritable();
      ImmutableBytesWritable value = new ImmutableBytesWritable();
      key.readFields(in);
      value.readFields(in);
      values.put(key, value);
    }
    families.clear();
    int numFamilies = in.readInt();
    for (int i = 0; i < numFamilies; i++) {
      HColumnDescriptor c = new HColumnDescriptor();
      c.readFields(in);
      families.put(Bytes.mapKey(c.getName()), c);
    }
  }

  /** {@inheritDoc} */
  public void write(DataOutput out) throws IOException {
	out.writeInt(TABLE_DESCRIPTOR_VERSION);
    Bytes.writeByteArray(out, name);
    out.writeBoolean(isRootRegion());
    out.writeBoolean(isMetaRegion());
    out.writeInt(values.size());
    for (Map.Entry<ImmutableBytesWritable, ImmutableBytesWritable> e:
        values.entrySet()) {
      e.getKey().write(out);
      e.getValue().write(out);
    }
    out.writeInt(families.size());
    for(Iterator<HColumnDescriptor> it = families.values().iterator();
        it.hasNext(); ) {
      HColumnDescriptor family = it.next();
      family.write(out);
    }
  }

  // Comparable

  /** {@inheritDoc} */
  public int compareTo(Object o) {
    HTableDescriptor other = (HTableDescriptor) o;
    int result = Bytes.compareTo(this.name, other.name);
    if (result == 0) {
      result = families.size() - other.families.size();
    }
    if (result == 0 && families.size() != other.families.size()) {
      result = Integer.valueOf(families.size()).compareTo(
          Integer.valueOf(other.families.size()));
    }
    if (result == 0) {
      for (Iterator<HColumnDescriptor> it = families.values().iterator(),
          it2 = other.families.values().iterator(); it.hasNext(); ) {
        result = it.next().compareTo(it2.next());
        if (result != 0) {
          break;
        }
      }
    }
    if (result == 0) {
      // punt on comparison for ordering, just calculate difference
      result = this.values.hashCode() - other.values.hashCode();
      if (result < 0)
        result = -1;
      else if (result > 0)
        result = 1;
    }
    return result;
  }

  /**
   * @return Immutable sorted map of families.
   */
  public Collection<HColumnDescriptor> getFamilies() {
    return Collections.unmodifiableCollection(this.families.values());
  }

  /**
   * @param column
   * @return Column descriptor for the passed family name or the family on
   * passed in column.
   */
  public HColumnDescriptor getFamily(final byte [] column) {
    return this.families.get(HStoreKey.getFamilyMapKey(column));
  }

  /**
   * @param column
   * @return Column descriptor for the passed family name or the family on
   * passed in column.
   */
  public HColumnDescriptor removeFamily(final byte [] column) {
    return this.families.remove(HStoreKey.getFamilyMapKey(column));
  }

  /**
   * @param rootdir qualified path of HBase root directory
   * @param tableName name of table
   * @return path for table
   */
  public static Path getTableDir(Path rootdir, final byte [] tableName) {
    return new Path(rootdir, Bytes.toString(tableName));
  }
}
