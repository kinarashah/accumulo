/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Map.Entry;

import jline.console.ConsoleReader;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.client.impl.Namespaces;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.file.FileOperations;
import org.apache.accumulo.core.file.FileSKVWriter;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.RootTable;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.core.util.shell.Shell;
import org.apache.accumulo.test.functional.FunctionalTestUtils;
import org.apache.accumulo.test.functional.SimpleMacIT;
import org.apache.accumulo.tracer.TraceServer;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.tools.DistCp;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class ShellServerIT extends SimpleMacIT {
  public static class TestOutputStream extends OutputStream {
    StringBuilder sb = new StringBuilder();

    @Override
    public void write(int b) throws IOException {
      sb.append((char) (0xff & b));
    }

    public String get() {
      return sb.toString();
    }

    public void clear() {
      sb.setLength(0);
    }
  }
  
  private static final Logger log = Logger.getLogger(ShellServerIT.class);

  public static class StringInputStream extends InputStream {
    private String source = "";
    private int offset = 0;

    @Override
    public int read() throws IOException {
      if (offset == source.length())
        return '\n';
      else
        return source.charAt(offset++);
    }

    public void set(String other) {
      source = other;
      offset = 0;
    }
  }

  public TestOutputStream output;
  public StringInputStream input;
  public Shell shell;
  private static Process traceProcess;
  
  @Rule
  public TestName name = new TestName();

  String exec(String cmd) throws IOException {
    output.clear();
    shell.execCommand(cmd, true, true);
    return output.get();
  }

  String exec(String cmd, boolean expectGoodExit) throws IOException {
    String result = exec(cmd);
    if (expectGoodExit)
      assertGoodExit("", true);
    else
      assertBadExit("", true);
    return result;
  }

  String exec(String cmd, boolean expectGoodExit, String expectString) throws IOException {
    return exec(cmd, expectGoodExit, expectString, true);
  }

  String exec(String cmd, boolean expectGoodExit, String expectString, boolean stringPresent) throws IOException {
    String result = exec(cmd);
    if (expectGoodExit)
      assertGoodExit(expectString, stringPresent);
    else
      assertBadExit(expectString, stringPresent);
    return result;
  }

  void assertGoodExit(String s, boolean stringPresent) {
    Shell.log.info(output.get());
    assertEquals(0, shell.getExitCode());

    if (s.length() > 0)
      assertEquals(s + " present in " + output.get() + " was not " + stringPresent, stringPresent, output.get().contains(s));
  }

  void assertBadExit(String s, boolean stringPresent) {
    Shell.log.debug(output.get());
    assertTrue(shell.getExitCode() > 0);
    if (s.length() > 0)
      assertEquals(s + " present in " + output.get() + " was not " + stringPresent, stringPresent, output.get().contains(s));
    shell.resetExitCode();
  }

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    // history file is updated in $HOME
    System.setProperty("HOME", getFolder().getAbsolutePath());
    System.setProperty("hadoop.tmp.dir", System.getProperty("user.dir") + "/target/hadoop-tmp");

    // use reflection to call this method so it does not need to be made public
    Method method = getStaticCluster().getClass().getDeclaredMethod("exec", Class.class, String[].class);
    method.setAccessible(true);
    traceProcess = (Process) method.invoke(getStaticCluster(), TraceServer.class, new String[0]);

    // give the tracer some time to start
    UtilWaitThread.sleep(1000);
  }
  
  @Before
  public void setupShell() throws Exception {
    // start the shell
    output = new TestOutputStream();
    input = new StringInputStream();
    shell = new Shell(new ConsoleReader(input, output));
    shell.setLogErrorsToConsole();
    shell.config("-u", "root", "-p", ROOT_PASSWORD, "-z", getStaticCluster().getConfig().getInstanceName(), getStaticCluster().getConfig().getZooKeepers(),
        "--config-file", getStaticCluster().getConfig().getClientConfFile().getAbsolutePath());
    exec("quit", true);
    shell.start();
    shell.setExit(false);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    traceProcess.destroy();
  }

  @After
  public void deleteTables() throws Exception {
    Connector c = getConnector();
    for (String table : c.tableOperations().list()) {
      if (!table.equals(MetadataTable.NAME) && !table.equals(RootTable.NAME) && !table.equals("trace"))
        try {
          c.tableOperations().delete(table);
        } catch (TableNotFoundException e) {
          // don't care
        }
    }
  }

  @After
  public void tearDownShell() {
    shell.shutdown();
  }

  @Test(timeout = 60000)
  public void exporttableImporttable() throws Exception {
    final String table = name.getMethodName(), table2 = table + "2";
    
    // exporttable / importtable
    exec("createtable " + table + " -evc", true);
    make10();
    exec("addsplits row5", true);
    exec("config -t " + table + " -s table.split.threshold=345M", true);
    exec("offline " + table, true);
    String export = "file://" + new File(getFolder(), "ShellServerIT.export").toString();
    exec("exporttable -t " + table + " " + export, true);
    DistCp cp = newDistCp();
    String import_ = "file://" + new File(getFolder(), "ShellServerIT.import").toString();
    cp.run(new String[] {"-f", export + "/distcp.txt", import_});
    exec("importtable " + table2 + " " + import_, true);
    exec("config -t " + table2 + " -np", true, "345M", true);
    exec("getsplits -t " + table2, true, "row5", true);
    exec("constraint --list -t " + table2, true, "VisibilityConstraint=2", true);
    exec("onlinetable " + table, true);
    exec("deletetable -f " + table, true);
    exec("deletetable -f " + table2, true);
  }

  private DistCp newDistCp() {
    try {
      @SuppressWarnings("unchecked")
      Constructor<DistCp>[] constructors = (Constructor<DistCp>[]) DistCp.class.getConstructors();
      for (Constructor<DistCp> constructor : constructors) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (parameterTypes.length > 0 && parameterTypes[0].equals(Configuration.class)) {
          if (parameterTypes.length == 1) {
            return constructor.newInstance(new Configuration());
          } else if (parameterTypes.length == 2) {
            return constructor.newInstance(new Configuration(), null);
          }
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    throw new RuntimeException("Unexpected constructors for DistCp");
  }

  @Test(timeout = 45000)
  public void setscaniterDeletescaniter() throws Exception {
    final String table = name.getMethodName();
    
    // setscaniter, deletescaniter
    exec("createtable " + table);
    exec("insert a cf cq 1");
    exec("insert a cf cq 1");
    exec("insert a cf cq 1");
    input.set("true\n\n\nSTRING");
    exec("setscaniter -class org.apache.accumulo.core.iterators.user.SummingCombiner -p 10 -n name", true);
    exec("scan", true, "3", true);
    exec("deletescaniter -n name", true);
    exec("scan", true, "1", true);
    exec("deletetable -f " + table);

  }

  @Test(timeout = 45000)
  public void execfile() throws Exception {
    // execfile
    File file = File.createTempFile("ShellServerIT.execfile", ".conf", getFolder());
    PrintWriter writer = new PrintWriter(file.getAbsolutePath());
    writer.println("about");
    writer.close();
    exec("execfile " + file.getAbsolutePath(), true, Constants.VERSION, true);

  }

  @Test(timeout = 45000)
  public void egrep() throws Exception {
    final String table = name.getMethodName();
    
    // egrep
    exec("createtable " + table);
    make10();
    String lines = exec("egrep row[123]", true);
    assertTrue(lines.split("\n").length - 1 == 3);
    exec("deletetable -f " + table);
  }

  @Test(timeout = 45000)
  public void du() throws Exception {
    final String table = name.getMethodName();
    
    // create and delete a table so we get out of a table context in the shell
    exec("notable", true);

    // Calling du not in a table context shouldn't throw an error
    output.clear();
    exec("du", true, "", true);

    output.clear();
    exec("createtable " + table);
    make10();
    exec("flush -t " + table + " -w");
    exec("du " + table, true, " [" + table + "]", true);
    output.clear();
    shell.execCommand("du -h", false, false);
    String o = output.get();
    // for some reason, there's a bit of fluctuation
    assertTrue("Output did not match regex: '" + o + "'", o.matches(".*[1-9][0-9][0-9]\\s\\[" + table + "\\]\\n"));
    exec("deletetable -f " + table);
  }

  @Test(timeout = 1000)
  public void debug() throws Exception {
    exec("debug", true, "off", true);
    exec("debug on", true);
    exec("debug", true, "on", true);
    exec("debug off", true);
    exec("debug", true, "off", true);
    exec("debug debug", false);
    exec("debug debug debug", false);
  }

  @Test(timeout = 45000)
  public void user() throws Exception {
    final String table = name.getMethodName();
    
    // createuser, deleteuser, user, users, droptable, grant, revoke
    input.set("secret\nsecret\n");
    exec("createuser xyzzy", true);
    exec("users", true, "xyzzy", true);
    String perms = exec("userpermissions -u xyzzy", true);
    assertTrue(perms.contains("Table permissions (" + MetadataTable.NAME + "): Table.READ"));
    exec("grant -u xyzzy -s System.CREATE_TABLE", true);
    perms = exec("userpermissions -u xyzzy", true);
    assertTrue(perms.contains(""));
    exec("grant -u root -t " + MetadataTable.NAME + " Table.WRITE", true);
    exec("grant -u root -t " + MetadataTable.NAME + " Table.GOOFY", false);
    exec("grant -u root -s foo", false);
    exec("grant -u xyzzy -t " + MetadataTable.NAME + " foo", false);
    input.set("secret\nsecret\n");
    exec("user xyzzy", true);
    exec("createtable " + table, true, "xyzzy@", true);
    exec("insert row1 cf cq 1", true);
    exec("scan", true, "row1", true);
    exec("droptable -f " + table, true);
    exec("deleteuser xyzzy", false, "delete yourself", true);
    input.set(ROOT_PASSWORD + "\n" + ROOT_PASSWORD + "\n");
    exec("user root", true);
    exec("revoke -u xyzzy -s System.CREATE_TABLE", true);
    exec("revoke -u xyzzy -s System.GOOFY", false);
    exec("revoke -u xyzzy -s foo", false);
    exec("revoke -u xyzzy -t " + MetadataTable.NAME + " Table.WRITE", true);
    exec("revoke -u xyzzy -t " + MetadataTable.NAME + " Table.GOOFY", false);
    exec("revoke -u xyzzy -t " + MetadataTable.NAME + " foo", false);
    exec("deleteuser xyzzy", true);
    exec("users", true, "xyzzy", false);
  }

  @Test(timeout = 45000)
  public void iter() throws Exception {
    final String table = name.getMethodName();
    
    // setshelliter, listshelliter, deleteshelliter
    exec("createtable " + table);
    exec("insert a cf cq 1");
    exec("insert a cf cq 1");
    exec("insert a cf cq 1");
    input.set("true\n\n\nSTRING\n");
    exec("setshelliter -class org.apache.accumulo.core.iterators.user.SummingCombiner -p 10 -pn sum -n name", true);
    exec("setshelliter -class org.apache.accumulo.core.iterators.user.SummingCombiner -p 11 -pn sum -n name", false);
    exec("setshelliter -class org.apache.accumulo.core.iterators.user.SummingCombiner -p 10 -pn sum -n other", false);
    input.set("true\n\n\nSTRING\n");
    exec("setshelliter -class org.apache.accumulo.core.iterators.user.SummingCombiner -p 11 -pn sum -n xyzzy", true);
    exec("scan -pn sum", true, "3", true);
    exec("listshelliter", true, "Iterator name", true);
    exec("listshelliter", true, "Iterator xyzzy", true);
    exec("listshelliter", true, "Profile : sum", true);
    exec("deleteshelliter -pn sum -n name", true);
    exec("listshelliter", true, "Iterator name", false);
    exec("listshelliter", true, "Iterator xyzzy", true);
    exec("deleteshelliter -pn sum -a", true);
    exec("listshelliter", true, "Iterator xyzzy", false);
    exec("listshelliter", true, "Profile : sum", false);
    exec("deletetable -f " + table);
    // list iter
    exec("createtable " + table);
    exec("insert a cf cq 1");
    exec("insert a cf cq 1");
    exec("insert a cf cq 1");
    input.set("true\n\n\nSTRING\n");
    exec("setiter -scan -class org.apache.accumulo.core.iterators.user.SummingCombiner -p 10 -n name", true);
    exec("setiter -scan -class org.apache.accumulo.core.iterators.user.SummingCombiner -p 11 -n name", false);
    exec("setiter -scan -class org.apache.accumulo.core.iterators.user.SummingCombiner -p 10 -n other", false);
    input.set("true\n\n\nSTRING\n");
    exec("setiter -scan -class org.apache.accumulo.core.iterators.user.SummingCombiner -p 11 -n xyzzy", true);
    exec("scan", true, "3", true);
    exec("listiter -scan", true, "Iterator name", true);
    exec("listiter -scan", true, "Iterator xyzzy", true);
    exec("listiter -minc", true, "Iterator name", false);
    exec("listiter -minc", true, "Iterator xyzzy", false);
    exec("deleteiter -scan -n name", true);
    exec("listiter -scan", true, "Iterator name", false);
    exec("listiter -scan", true, "Iterator xyzzy", true);
    exec("deletetable -f " + table);

  }

  @Test(timeout = 45000)
  public void setIterOptionPrompt() throws Exception {
    Connector conn = getConnector();
    String tableName = name.getMethodName();

    exec("createtable " + tableName);
    input.set("\n\n");
    // Setting a non-optiondescriber with no name should fail
    exec("setiter -scan -class org.apache.accumulo.core.iterators.ColumnFamilyCounter -p 30", false);

    // Name as option will work
    exec("setiter -scan -class org.apache.accumulo.core.iterators.ColumnFamilyCounter -p 30 -name cfcounter", true);

    String expectedKey = "table.iterator.scan.cfcounter";
    String expectedValue = "30,org.apache.accumulo.core.iterators.ColumnFamilyCounter";
    TableOperations tops = conn.tableOperations();
    checkTableForProperty(tops, tableName, expectedKey, expectedValue);

    exec("deletetable " + tableName, true);
    tableName = tableName + "1";

    exec("createtable " + tableName, true);

    input.set("customcfcounter\n\n");

    // Name on the CLI should override OptionDescriber (or user input name, in this case)
    exec("setiter -scan -class org.apache.accumulo.core.iterators.ColumnFamilyCounter -p 30", true);
    expectedKey = "table.iterator.scan.customcfcounter";
    expectedValue = "30,org.apache.accumulo.core.iterators.ColumnFamilyCounter";
    checkTableForProperty(tops, tableName, expectedKey, expectedValue);

    exec("deletetable " + tableName, true);
    tableName = tableName + "1";

    exec("createtable " + tableName, true);

    input.set("customcfcounter\nname1 value1\nname2 value2\n\n");

    // Name on the CLI should override OptionDescriber (or user input name, in this case)
    exec("setiter -scan -class org.apache.accumulo.core.iterators.ColumnFamilyCounter -p 30", true);
    expectedKey = "table.iterator.scan.customcfcounter";
    expectedValue = "30,org.apache.accumulo.core.iterators.ColumnFamilyCounter";
    checkTableForProperty(tops, tableName, expectedKey, expectedValue);
    expectedKey = "table.iterator.scan.customcfcounter.opt.name1";
    expectedValue = "value1";
    checkTableForProperty(tops, tableName, expectedKey, expectedValue);
    expectedKey = "table.iterator.scan.customcfcounter.opt.name2";
    expectedValue = "value2";
    checkTableForProperty(tops, tableName, expectedKey, expectedValue);

    exec("deletetable " + tableName, true);
    tableName = tableName + "1";

    exec("createtable " + tableName, true);

    input.set("\nname1 value1.1,value1.2,value1.3\nname2 value2\n\n");

    // Name on the CLI should override OptionDescriber (or user input name, in this case)
    exec("setiter -scan -class org.apache.accumulo.core.iterators.ColumnFamilyCounter -p 30 -name cfcounter", true);
    expectedKey = "table.iterator.scan.cfcounter";
    expectedValue = "30,org.apache.accumulo.core.iterators.ColumnFamilyCounter";
    checkTableForProperty(tops, tableName, expectedKey, expectedValue);
    expectedKey = "table.iterator.scan.cfcounter.opt.name1";
    expectedValue = "value1.1,value1.2,value1.3";
    checkTableForProperty(tops, tableName, expectedKey, expectedValue);
    expectedKey = "table.iterator.scan.cfcounter.opt.name2";
    expectedValue = "value2";
    checkTableForProperty(tops, tableName, expectedKey, expectedValue);
  }

  protected void checkTableForProperty(TableOperations tops, String tableName, String expectedKey, String expectedValue) throws Exception {
    for (int i = 0; i < 5; i++) {
      for (Entry<String,String> entry : tops.getProperties(tableName)) {
        if (expectedKey.equals(entry.getKey())) {
          assertEquals(expectedValue, entry.getValue());
          return;
        }
      }
      Thread.sleep(500);
    }

    fail("Failed to find expected property on " + tableName + ": " + expectedKey + "=" + expectedValue);
  }

  @Test(timeout = 45000)
  public void notable() throws Exception {
    final String table = name.getMethodName();
    
    // notable
    exec("createtable " + table, true);
    exec("scan", true, " " + table + ">", true);
    assertTrue(output.get().contains(" " + table + ">"));
    exec("notable", true);
    exec("scan", false, "Not in a table context.", true);
    assertFalse(output.get().contains(" " + table + ">"));
    exec("deletetable -f " + table);
  }

  @Test(timeout = 45000)
  public void sleep() throws Exception {
    // sleep
    long now = System.currentTimeMillis();
    exec("sleep 0.2", true);
    long diff = System.currentTimeMillis() - now;
    assertTrue("Diff was actually " + diff, diff >= 200);
    assertTrue("Diff was actually " + diff, diff < 600);
  }

  @Test(timeout = 45000)
  public void addauths() throws Exception {
    final String table = name.getMethodName();
    // addauths
    exec("createtable " + table + " -evc");
    exec("insert a b c d -l foo", false, "does not have authorization", true);
    exec("addauths -s foo,bar", true);
    exec("getauths", true, "foo", true);
    exec("getauths", true, "bar", true);
    exec("insert a b c d -l foo");
    exec("scan", true, "[foo]");
    exec("scan -s bar", true, "[foo]", false);
    exec("deletetable -f " + table);
  }

  @Test(timeout = 45000)
  public void byeQuitExit() throws Exception {
    // bye, quit, exit
    for (String cmd : "bye quit exit".split(" ")) {
      assertFalse(shell.getExit());
      exec(cmd);
      assertTrue(shell.getExit());
      shell.setExit(false);
    }
  }

  @Test(timeout = 45000)
  public void classpath() throws Exception {
    // classpath
    exec("classpath", true, "Level 2: Java Classloader (loads everything defined by java classpath) URL classpath items are", true);
  }

  @Test(timeout = 45000)
  public void clearCls() throws Exception {
    // clear/cls
    if (shell.getReader().getTerminal().isAnsiSupported()) {
      exec("cls", true, "[1;1H");
      exec("clear", true, "[2J");
    } else {
      exec("cls", false, "does not support");
      exec("clear", false, "does not support");
    }
  }

  @Test(timeout = 45000)
  public void clonetable() throws Exception {
    final String table = name.getMethodName(), clone = table + "_clone";
    
    // clonetable
    exec("createtable " + table + " -evc");
    exec("config -t " + table + " -s table.split.threshold=123M", true);
    exec("addsplits -t " + table + " a b c", true);
    exec("insert a b c value");
    exec("scan", true, "value", true);
    exec("clonetable " + table + " " + clone);
    // verify constraint, config, and splits were cloned
    exec("constraint --list -t " + clone, true, "VisibilityConstraint=2", true);
    exec("config -t " + clone + " -np", true, "123M", true);
    exec("getsplits -t " + clone, true, "a\nb\nc\n");
    exec("deletetable -f " + table);
    exec("deletetable -f " + clone);
  }
  
  @Test(timeout = 45000)
  public void testCompactions() throws Exception {
    final String table = name.getMethodName();
    
    // compact
    exec("createtable " + table);
    
    String tableId = getTableId(table);
    
    // make two files
    exec("insert a b c d");
    exec("flush -w");
    exec("insert x y z v");
    exec("flush -w");
    int oldCount = countFiles(tableId);
    // merge two files into one
    exec("compact -t " + table + " -w");
    assertTrue(countFiles(tableId) < oldCount);
    exec("addsplits -t " + table + " f");
    // make two more files:
    exec("insert m 1 2 3");
    exec("flush -w");
    exec("insert n 1 2 3");
    exec("flush -w");
    oldCount = countFiles(tableId);

    // at this point there are 4 files in the default tablet
    assertEquals(4, oldCount);
    
    // compact some data:
    exec("compact -b g -e z -w");
    assertEquals(2, countFiles(tableId));
    exec("compact -w");
    assertEquals(2, countFiles(tableId));
    exec("merge --all -t " + table);
    exec("compact -w");
    assertEquals(1, countFiles(tableId));
    exec("deletetable -f " + table);
  }

  @Test(timeout = 45000)
  public void constraint() throws Exception {
    final String table = name.getMethodName();
    
    // constraint
    exec("constraint -l -t " + MetadataTable.NAME + "", true, "MetadataConstraints=1", true);
    exec("createtable " + table + " -evc");

    // Make sure the table is fully propagated through zoocache
    getTableId(table);

    exec("constraint -l -t " + table, true, "VisibilityConstraint=2", true);
    exec("constraint -t " + table + " -d 2", true, "Removed constraint 2 from table " + table);
    // wait for zookeeper updates to propagate
    UtilWaitThread.sleep(1000);
    exec("constraint -l -t " + table, true, "VisibilityConstraint=2", false);
    exec("deletetable -f " + table);
  }

  @Test(timeout = 45000)
  public void deletemany() throws Exception {
    final String table = name.getMethodName();
    
    // deletemany
    exec("createtable " + table);
    make10();
    assertEquals(10, countkeys(table));
    exec("deletemany -f -b row8");
    assertEquals(8, countkeys(table));
    exec("scan -t " + table + " -np", true, "row8", false);
    make10();
    exec("deletemany -f -b row4 -e row5");
    assertEquals(8, countkeys(table));
    make10();
    exec("deletemany -f -c cf:col4,cf:col5");
    assertEquals(8, countkeys(table));
    make10();
    exec("deletemany -f -r row3");
    assertEquals(9, countkeys(table));
    make10();
    exec("deletemany -f -r row3");
    assertEquals(9, countkeys(table));
    make10();
    exec("deletemany -f -b row3 -be -e row5 -ee");
    assertEquals(9, countkeys(table));
    exec("deletetable -f " + table);
  }

  @Test(timeout = 45000)
  public void deleterows() throws Exception {
    final String table = name.getMethodName();

    exec("createtable " + table);
    final String tableId = getTableId(table);
    
    // deleterows
    int base = countFiles(tableId);
    assertEquals(0, base);
    
    exec("addsplits row5 row7");
    make10();
    exec("flush -w -t " + table);
    assertEquals(3, countFiles(tableId));
    exec("deleterows -t " + table + " -b row5 -e row7", true);
    assertEquals(2, countFiles(tableId));
    exec("deletetable -f " + table);
  }

  @Test(timeout = 45000)
  public void groups() throws Exception {
    final String table = name.getMethodName();
    
    exec("createtable " + table);
    exec("setgroups -t " + table + " alpha=a,b,c num=3,2,1");
    exec("getgroups -t " + table, true, "alpha=a,b,c", true);
    exec("getgroups -t " + table, true, "num=1,2,3", true);
    exec("deletetable -f " + table);
  }

  @Test(timeout = 45000)
  public void grep() throws Exception {
    final String table = name.getMethodName();
    
    exec("createtable " + table, true);
    make10();
    exec("grep row[123]", true, "row1", false);
    exec("grep row5", true, "row5", true);
    exec("deletetable -f " + table, true);
  }

  @Test(timeout = 45000)
  public void help() throws Exception {
    exec("help -np", true, "Help Commands", true);
    exec("?", true, "Help Commands", true);
    for (String c : ("bye exit quit " + "about help info ? " + "deleteiter deletescaniter listiter setiter setscaniter "
        + "grant revoke systempermissions tablepermissions userpermissions " + "execfile history " + "authenticate cls clear notable sleep table user whoami "
        + "clonetable config createtable deletetable droptable du exporttable importtable offline online renametable tables "
        + "addsplits compact constraint flush getgropus getsplits merge setgroups " + "addauths createuser deleteuser dropuser getauths passwd setauths users "
        + "delete deletemany deleterows egrep formatter interpreter grep importdirectory insert maxrow scan").split(" ")) {
      exec("help " + c, true);
    }
  }

  // @Test(timeout = 45000)
  public void history() throws Exception {
    final String table = name.getMethodName();
    
    exec("history -c", true);
    exec("createtable " + table);
    exec("deletetable -f " + table);
    exec("history", true, table, true);
    exec("history", true, "history", true);
  }

  @Test(timeout = 45000)
  public void importDirectory() throws Exception {
    final String table = name.getMethodName();
    
    Configuration conf = new Configuration();
    FileSystem fs = FileSystem.get(conf);
    File importDir = new File(getFolder(), "import");
    importDir.mkdir();
    String even = new File(importDir, "even.rf").toString();
    String odd = new File(importDir, "odd.rf").toString();
    File errorsDir = new File(getFolder(), "errors");
    errorsDir.mkdir();
    fs.mkdirs(new Path(errorsDir.toString()));
    AccumuloConfiguration aconf = AccumuloConfiguration.getDefaultConfiguration();
    FileSKVWriter evenWriter = FileOperations.getInstance().openWriter(even, fs, conf, aconf);
    evenWriter.startDefaultLocalityGroup();
    FileSKVWriter oddWriter = FileOperations.getInstance().openWriter(odd, fs, conf, aconf);
    oddWriter.startDefaultLocalityGroup();
    long ts = System.currentTimeMillis();
    Text cf = new Text("cf");
    Text cq = new Text("cq");
    Value value = new Value("value".getBytes());
    for (int i = 0; i < 100; i += 2) {
      Key key = new Key(new Text(String.format("%8d", i)), cf, cq, ts);
      evenWriter.append(key, value);
      key = new Key(new Text(String.format("%8d", i + 1)), cf, cq, ts);
      oddWriter.append(key, value);
    }
    evenWriter.close();
    oddWriter.close();
    assertEquals(0, shell.getExitCode());
    exec("createtable " + table, true);
    exec("importdirectory " + importDir + " " + errorsDir + " true", true);
    exec("scan -r 00000000", true, "00000000", true);
    exec("scan -r 00000099", true, "00000099", true);
    exec("deletetable -f " + table);
  }

  @Test(timeout = 45000)
  public void info() throws Exception {
    exec("info", true, Constants.VERSION, true);
  }

  @Test(timeout = 45000)
  public void interpreter() throws Exception {
    final String table = name.getMethodName();
    
    exec("createtable " + table, true);
    exec("interpreter -l", true, "HexScan", false);
    exec("insert \\x02 cf cq value", true);
    exec("scan -b 02", true, "value", false);
    exec("interpreter -i org.apache.accumulo.core.util.interpret.HexScanInterpreter", true);
    // Need to allow time for this to propagate through zoocache/zookeeper
    UtilWaitThread.sleep(3000);

    exec("interpreter -l", true, "HexScan", true);
    exec("scan -b 02", true, "value", true);
    exec("deletetable -f " + table, true);
  }

  @Test(timeout = 45000)
  public void listcompactions() throws Exception {
    final String table = name.getMethodName();
    
    exec("createtable " + table, true);
    exec("config -t " + table + " -s table.iterator.minc.slow=30,org.apache.accumulo.test.functional.SlowIterator", true);
    exec("config -t " + table + " -s table.iterator.minc.slow.opt.sleepTime=1000", true);
    exec("insert a cf cq value", true);
    exec("insert b cf cq value", true);
    exec("insert c cf cq value", true);
    exec("insert d cf cq value", true);
    exec("flush -t " + table, true);
    exec("sleep 0.2", true);
    exec("listcompactions", true, "default_tablet");
    String[] lines = output.get().split("\n");
    String last = lines[lines.length - 1];
    String[] parts = last.split("\\|");
    assertEquals(12, parts.length);
    exec("deletetable -f " + table, true);
  }

  @Test(timeout = 45000)
  public void maxrow() throws Exception {
    final String table = name.getMethodName();
    
    exec("createtable " + table, true);
    exec("insert a cf cq value", true);
    exec("insert b cf cq value", true);
    exec("insert ccc cf cq value", true);
    exec("insert zzz cf cq value", true);
    exec("maxrow", true, "zzz", true);
    exec("delete zzz cf cq", true);
    exec("maxrow", true, "ccc", true);
    exec("deletetable -f " + table, true);
  }

  @Test(timeout = 45000)
  public void merge() throws Exception {
    final String table = name.getMethodName();
    
    exec("createtable " + table);
    exec("addsplits a m z");
    exec("getsplits", true, "z", true);
    exec("merge --all", true);
    exec("getsplits", true, "z", false);
    exec("deletetable -f " + table);
    exec("getsplits -t " + MetadataTable.NAME + "", true);
    assertEquals(2, output.get().split("\n").length);
    exec("getsplits -t accumulo.root", true);
    assertEquals(1, output.get().split("\n").length);
    exec("merge --all -t " + MetadataTable.NAME + "");
    exec("getsplits -t " + MetadataTable.NAME + "", true);
    assertEquals(1, output.get().split("\n").length);
  }

  @Test(timeout = 45000)
  public void ping() throws Exception {
    for (int i = 0; i < 10; i++) {
      exec("ping", true, "OK", true);
      // wait for both tservers to start up
      if (output.get().split("\n").length == 3)
        break;
      UtilWaitThread.sleep(1000);

    }
    assertEquals(3, output.get().split("\n").length);
  }

  @Test(timeout = 45000)
  public void renametable() throws Exception {
    final String table = name.getMethodName() + "1", rename = name.getMethodName() + "2";
    
    exec("createtable " + table);
    exec("insert this is a value");
    exec("renametable " + table + " " + rename);
    exec("tables", true, rename, true);
    exec("tables", true, table, false);
    exec("scan -t " + rename, true, "value", true);
    exec("deletetable -f " + rename, true);
  }

  @Test(timeout = 30000)
  public void tables() throws Exception {
    final String table = name.getMethodName(), table1 = table + "_z", table2 = table + "_a";
    exec("createtable " + table1);
    exec("createtable " + table2);
    exec("notable");
    String lst = exec("tables -l");
    assertTrue(lst.indexOf(table2) < lst.indexOf(table1));
    lst = exec("tables -l -s");
    assertTrue(lst.indexOf(table1) < lst.indexOf(table2));
  }

  @Test(timeout = 45000)
  public void systempermission() throws Exception {
    exec("systempermissions");
    assertEquals(11, output.get().split("\n").length - 1);
    exec("tablepermissions", true);
    assertEquals(6, output.get().split("\n").length - 1);
  }

  @Test(timeout = 45000)
  public void listscans() throws Exception {
    final String table = name.getMethodName();
    
    exec("createtable " + table, true);
    exec("config -t " + table + " -s table.iterator.scan.slow=30,org.apache.accumulo.test.functional.SlowIterator", true);
    exec("config -t " + table + " -s table.iterator.scan.slow.opt.sleepTime=1000", true);
    exec("insert a cf cq value", true);
    exec("insert b cf cq value", true);
    exec("insert c cf cq value", true);
    exec("insert d cf cq value", true);
    Thread thread = new Thread() {
      @Override
      public void run() {
        try {
          Connector connector = getConnector();
          Scanner s = connector.createScanner(table, Authorizations.EMPTY);
          FunctionalTestUtils.count(s);
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      }
    };
    thread.start();
    exec("sleep 0.1", true);
    String scans = exec("listscans", true);
    String lines[] = scans.split("\n");
    String last = lines[lines.length - 1];
    assertTrue(last.contains("RUNNING"));
    String parts[] = last.split("\\|");
    assertEquals(13, parts.length);
    String hostPortPattern = ".+:\\d+";
    String tserver = parts[0].trim();
    assertTrue(tserver.matches(hostPortPattern));
    assertTrue(getConnector().instanceOperations().getTabletServers().contains(tserver));
    String client = parts[1].trim();
    assertTrue(client.matches(hostPortPattern));
    // TODO: any way to tell if the client address is accurate? could be local IP, host, loopback...?
    thread.join();
    exec("deletetable -f " + table, true);
  }

  @Test(timeout = 45000)
  public void testPertableClasspath() throws Exception {
    final String table = name.getMethodName();

    File fooFilterJar = File.createTempFile("FooFilter", ".jar", getFolder());
    
    FileUtils.copyURLToFile(this.getClass().getResource("/FooFilter.jar"), fooFilterJar);
    fooFilterJar.deleteOnExit();

    File fooConstraintJar = File.createTempFile("FooConstraint", ".jar", getFolder());
    FileUtils.copyURLToFile(this.getClass().getResource("/FooConstraint.jar"), fooConstraintJar);
    fooConstraintJar.deleteOnExit();

    exec(
        "config -s " + Property.VFS_CONTEXT_CLASSPATH_PROPERTY.getKey() + "cx1=" + fooFilterJar.toURI().toString() + "," + fooConstraintJar.toURI().toString(),
        true);

    exec("createtable " + table, true);
    exec("config -t " + table + " -s " + Property.TABLE_CLASSPATH.getKey() + "=cx1", true);

    UtilWaitThread.sleep(200);

    // We can't use the setiter command as Filter implements OptionDescriber which
    // forces us to enter more input that I don't know how to input
    // Instead, we can just manually set the property on the table.
    exec("config -t " + table + " -s " + Property.TABLE_ITERATOR_PREFIX.getKey() + "scan.foo=10,org.apache.accumulo.test.FooFilter");

    exec("insert foo f q v", true);

    UtilWaitThread.sleep(100);

    exec("scan -np", true, "foo", false);

    exec("constraint -a FooConstraint", true);

    exec("offline -w " + table);
    exec("online -w " + table);

    exec("table " + table, true);
    exec("insert foo f q v", false);
    exec("insert ok foo q v", true);

    exec("deletetable -f " + table, true);
    exec("config -d " + Property.VFS_CONTEXT_CLASSPATH_PROPERTY.getKey() + "cx1");

  }

  @Test(timeout = 45000)
  public void trace() throws Exception {
    // Make sure to not collide with the "trace" table
    final String table = name.getMethodName() + "Test";
    
    exec("trace on", true);
    exec("createtable " + table, true);
    exec("insert a b c value", true);
    exec("scan -np", true, "value", true);
    exec("deletetable -f " + table);
    exec("sleep 1");
    String trace = exec("trace off");
    System.out.println(trace);
    assertTrue(trace.contains("sendMutations"));
    assertTrue(trace.contains("startScan"));
    assertTrue(trace.contains("DeleteTable"));
  }

  @Test(timeout = 30000)
  public void badLogin() throws Exception {
    input.set(ROOT_PASSWORD + "\n");
    String err = exec("user NoSuchUser", false);
    assertTrue(err.contains("BAD_CREDENTIALS for user NoSuchUser"));
  }

  @Test(timeout = 30000)
  public void namespaces() throws Exception {
    exec("namespaces", true, "\"\"", true); // default namespace, displayed as quoted empty string
    exec("namespaces", true, Namespaces.ACCUMULO_NAMESPACE, true);
    exec("createnamespace thing1", true);
    String namespaces = exec("namespaces");
    assertTrue(namespaces.contains("thing1"));

    exec("renamenamespace thing1 thing2");
    namespaces = exec("namespaces");
    assertTrue(namespaces.contains("thing2"));
    assertTrue(!namespaces.contains("thing1"));

    // can't delete a namespace that still contains tables, unless you do -f
    exec("createtable thing2.thingy", true);
    exec("deletenamespace thing2");
    exec("y");
    exec("namespaces", true, "thing2", true);

    exec("du -ns thing2", true, "thing2.thingy", true);

    // all "TableOperation" commands can take a namespace
    exec("offline -ns thing2", true);
    exec("online -ns thing2", true);
    exec("flush -ns thing2", true);
    exec("compact -ns thing2", true);
    exec("createnamespace testers3", true);
    exec("createtable testers3.1", true);
    exec("createtable testers3.2", true);
    exec("deletetable -ns testers3 -f", true);
    exec("tables", true, "testers3.1", false);
    exec("namespaces", true, "testers3", true);
    exec("deletenamespace testers3 -f", true);
    input.set("true\n\n\nSTRING\n");
    exec("setiter -ns thing2 -scan -class org.apache.accumulo.core.iterators.user.SummingCombiner -p 10 -n name", true);
    exec("listiter -ns thing2 -scan", true, "Summing", true);
    exec("deleteiter -ns thing2 -n name -scan", true);
    exec("createuser dude");
    exec("pass");
    exec("pass");
    exec("grant Namespace.CREATE_TABLE -ns thing2 -u dude", true);
    exec("revoke Namespace.CREATE_TABLE -ns thing2 -u dude", true);

    // properties override and such
    exec("config -ns thing2 -s table.file.max=44444", true);
    exec("config -ns thing2", true, "44444", true);
    exec("config -t thing2.thingy", true, "44444", true);
    exec("config -t thing2.thingy -s table.file.max=55555", true);
    exec("config -t thing2.thingy", true, "55555", true);

    // can copy properties when creating
    exec("createnamespace thing3 -cc thing2", true);
    exec("config -ns thing3", true, "44444", true);

    exec("deletenamespace -f thing2", true);
    exec("namespaces", true, "thing2", false);
    exec("tables", true, "thing2.thingy", false);

    // put constraints on a namespace
    exec("constraint -ns thing3 -a org.apache.accumulo.examples.simple.constraints.NumericValueConstraint", true);
    exec("createtable thing3.constrained", true);
    exec("table thing3.constrained", true);
    exec("constraint -d 1");
    // should fail
    exec("constraint -l", true, "NumericValueConstraint", true);
    exec("insert r cf cq abc", false);
    exec("constraint -ns thing3 -d 1");
    exec("sleep 1");
    exec("insert r cf cq abc", true);
  }

  private int countkeys(String table) throws IOException {
    exec("scan -np -t " + table);
    return output.get().split("\n").length - 1;
  }

  @Test(timeout = 30000)
  public void scans() throws Exception {
    exec("createtable t");
    make10();
    String result = exec("scan -np -b row1 -e row1");
    assertEquals(2, result.split("\n").length);
    result = exec("scan -np -b row3 -e row5");
    assertEquals(4, result.split("\n").length);
    result = exec("scan -np -r row3");
    assertEquals(2, result.split("\n").length);
    result = exec("scan -np -b row:");
    assertEquals(1, result.split("\n").length);
    result = exec("scan -np -b row");
    assertEquals(11, result.split("\n").length);
    result = exec("scan -np -e row:");
    assertEquals(11, result.split("\n").length);
    exec("deletetable -f t");
  }

  @Test(timeout = 30000)
  public void whoami() throws Exception {
    assertTrue(exec("whoami", true).contains("root"));
    input.set("secret\nsecret\n");
    exec("createuser test_user");
    exec("setauths -u test_user -s 12,3,4");
    String auths = exec("getauths -u test_user");
    assertTrue(auths.contains("3") && auths.contains("12") && auths.contains("4"));
    input.set("secret\n");
    exec("user test_user", true);
    assertTrue(exec("whoami", true).contains("test_user"));
    input.set(ROOT_PASSWORD + "\n");
    exec("user root", true);
  }

  private void make10() throws IOException {
    for (int i = 0; i < 10; i++) {
      exec(String.format("insert row%d cf col%d value", i, i));
    }
  }

  private int countFiles(String tableId) throws IOException {
    exec("scan -t " + MetadataTable.NAME + " -np -c file -b " + tableId + " -e " + tableId + "~");
    
    log.debug("countFiles(): " + output.get());
    
    return output.get().split("\n").length - 1;
  }
  
  private String getTableId(String tableName) throws Exception {
    Connector conn = getConnector();
    
    for (int i = 0; i < 5; i++) {
      Map<String,String> nameToId = conn.tableOperations().tableIdMap();
      if (nameToId.containsKey(tableName)) {
        return nameToId.get(tableName);
      } else {
        Thread.sleep(1000);
      }
    }
    
    fail("Could not find ID for table: " + tableName);
    // Will never get here
    return null;
  }

}
