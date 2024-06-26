/**
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

package org.apache.hadoop.hdfs;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.SequenceFile.Writer;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.util.concurrent.HadoopExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This program executes a specified operation that applies load to 
 * the NameNode.
 * 
 * When run simultaneously on multiple nodes, this program functions 
 * as a stress-test and benchmark for namenode, especially when 
 * the number of bytes written to each file is small.
 * 
 * Valid operations are:
 *   create_write
 *   open_read
 *   rename
 *   delete
 * 
 * NOTE: The open_read, rename and delete operations assume that the files
 *       they operate on are already available. The create_write operation 
 *       must be run before running the other operations.
 */

public class NNBench extends Configured implements Tool {
  private static final Logger LOG = LoggerFactory.getLogger(NNBench.class);
  
  private static String CONTROL_DIR_NAME = "control";
  private static String OUTPUT_DIR_NAME = "output";
  private static String DATA_DIR_NAME = "data";
  static final String DEFAULT_RES_FILE_NAME = "NNBench_results.log";
  private static final String NNBENCH_VERSION = "NameNode Benchmark 0.4";

  private String operation = "none";
  private long numberOfMaps = 1l; // default is 1
  private long numberOfReduces = 1l; // default is 1
  private long startTime =
          System.currentTimeMillis() + (120 * 1000); // default is 'now' + 2min
  private long blockSize = 1l; // default is 1
  private int bytesToWrite = 0; // default is 0
  private long bytesPerChecksum = 1l; // default is 1
  private long numberOfFiles = 1l; // default is 1
  private short replicationFactorPerFile = 1; // default is 1
  private String baseDir = "/benchmarks/NNBench";  // default
  private boolean readFileAfterOpen = false; // default is to not read
  private boolean isHelpMessage = false;
  // Supported operations
  private static final String OP_CREATE_WRITE = "create_write";
  private static final String OP_OPEN_READ = "open_read";
  private static final String OP_RENAME = "rename";
  private static final String OP_DELETE = "delete";
  private static final int MAX_OPERATION_EXCEPTIONS = 1000;
  
  // To display in the format that matches the NN and DN log format
  // Example: 2007-10-26 00:01:19,853
  static SimpleDateFormat sdf = 
          new SimpleDateFormat("yyyy-MM-dd' 'HH:mm:ss','S");
  
  /**
   * Clean up the files before a test run
   * 
   * @throws IOException on error
   */
  private void cleanupBeforeTestrun() throws IOException {
    FileSystem tempFS = FileSystem.get(new Path(baseDir).toUri(), getConf());
    
    // Delete the data directory only if it is the create/write operation
    if (operation.equals(OP_CREATE_WRITE)) {
      LOG.info("Deleting data directory");
      tempFS.delete(new Path(baseDir, DATA_DIR_NAME), true);
    }
    tempFS.delete(new Path(baseDir, CONTROL_DIR_NAME), true);
    tempFS.delete(new Path(baseDir, OUTPUT_DIR_NAME), true);
  }
  
  /**
   * Create control files before a test run.
   * Number of files created is equal to the number of maps specified
   * 
   * @throws IOException on error
   */
  private void createControlFiles() {
    LOG.info("Creating " + numberOfMaps + " control files");

    ExecutorService executorService =
        HadoopExecutors.newFixedThreadPool(2 * Runtime.getRuntime().availableProcessors());
    List<Future<Void>> list = new ArrayList<>();
    for (int i = 0; i < numberOfMaps; i++) {
      String strFileName = "NNBench_Controlfile_" + i;
      Path filePath = new Path(new Path(baseDir, CONTROL_DIR_NAME),
              strFileName);

      Future<Void> future = executorService.submit(new CreateControlFile(strFileName, filePath, i));
      list.add(future);
    }

    for (int i = 0; i < list.size(); i++) {
      try {
        list.get(i).get();
      } catch (InterruptedException | ExecutionException e) {
        LOG.error("Creating control files Error.", e);
      }
    }

    executorService.shutdown();
  }

  private class CreateControlFile implements Callable<Void> {
    private String strFileName;
    private Path filePath;
    private int order;

    CreateControlFile(String strFileName, Path filePath, int order) {
      this.strFileName = strFileName;
      this.filePath = filePath;
      this.order = order;
    }

    @Override
    public Void call() throws Exception {
      SequenceFile.Writer writer = null;
      try {
        writer = SequenceFile.createWriter(getConf(), Writer.file(filePath),
            Writer.keyClass(Text.class), Writer.valueClass(LongWritable.class),
            Writer.compression(CompressionType.NONE));
        writer.append(new Text(strFileName), new LongWritable(order));
      } finally {
        if (writer != null) {
          writer.close();
        }
      }
      return null;
    }

  }

  /**
   * Display version
   */
  private static void displayVersion() {
    System.out.println(NNBENCH_VERSION);
  }
  
  /**
   * Display usage
   */
  private static void displayUsage() {
    String usage =
      "Usage: nnbench <options>\n" +
      "Options:\n" +
      "\t-operation <Available operations are " + OP_CREATE_WRITE + " " +
      OP_OPEN_READ + " " + OP_RENAME + " " + OP_DELETE + ". " +
      "This option is mandatory>\n" +
      "\t * NOTE: The open_read, rename and delete operations assume " +
      "that the files they operate on, are already available. " +
      "The create_write operation must be run before running the " +
      "other operations.\n" +
      "\t-maps <number of maps. default is 1. This is not mandatory>\n" +
      "\t-reduces <number of reduces. default is 1. This is not mandatory>\n" +
      "\t-startTime <time to start, given in seconds from the epoch. " +
      "Make sure this is far enough into the future, so all maps " +
      "(operations) will start at the same time. " +
      "default is launch time + 2 mins. This is not mandatory>\n" +
      "\t-blockSize <Block size in bytes. default is 1. " + 
      "This is not mandatory>\n" +
      "\t-bytesToWrite <Bytes to write. default is 0. " + 
      "This is not mandatory>\n" +
      "\t-bytesPerChecksum <Bytes per checksum for the files. default is 1. " + 
      "This is not mandatory>\n" +
      "\t-numberOfFiles <number of files to create. default is 1. " +
      "This is not mandatory>\n" +
      "\t-replicationFactorPerFile <Replication factor for the files." +
        " default is 1. This is not mandatory>\n" +
      "\t-baseDir <base DFS path. default is /benchmarks/NNBench. " +
        "Supports cross-cluster access by using full path with schema and " +
        "cluster. This is not mandatory>\n" +
      "\t-readFileAfterOpen <true or false. if true, it reads the file and " +
      "reports the average time to read. This is valid with the open_read " +
      "operation. default is false. This is not mandatory>\n" +
      "\t-help: Display the help statement\n";
      
    
    System.out.println(usage);
  }

  /**
   * check for arguments and fail if the values are not specified
   * @param index  positional number of an argument in the list of command
   *   line's arguments
   * @param length total number of arguments
   */
  private static void checkArgs(final int index, final int length) {
    if (index == length) {
      displayUsage();
      throw new HadoopIllegalArgumentException("Not enough arguments");
    }
  }
  
  /**
   * Parse input arguments
   *  @param args array of command line's parameters to be parsed
   *
   */
  private void parseInputs(final String[] args) {
    // If there are no command line arguments, exit
    if (args.length == 0) {
      displayUsage();
      throw new HadoopIllegalArgumentException("Give valid inputs");
    }
    
    // Parse command line args
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("-operation")) {
        operation = args[++i];
      } else if (args[i].equals("-maps")) {
        checkArgs(i + 1, args.length);
        numberOfMaps = Long.parseLong(args[++i]);
      } else if (args[i].equals("-reduces")) {
        checkArgs(i + 1, args.length);
        numberOfReduces = Long.parseLong(args[++i]);
      } else if (args[i].equals("-startTime")) {
        checkArgs(i + 1, args.length);
        startTime = Long.parseLong(args[++i]) * 1000;
      } else if (args[i].equals("-blockSize")) {
        checkArgs(i + 1, args.length);
        blockSize = Long.parseLong(args[++i]);
      } else if (args[i].equals("-bytesToWrite")) {
        checkArgs(i + 1, args.length);
        bytesToWrite = Integer.parseInt(args[++i]);
      } else if (args[i].equals("-bytesPerChecksum")) {
        checkArgs(i + 1, args.length);
        bytesPerChecksum = Long.parseLong(args[++i]);
      } else if (args[i].equals("-numberOfFiles")) {
        checkArgs(i + 1, args.length);
        numberOfFiles = Long.parseLong(args[++i]);
      } else if (args[i].equals("-replicationFactorPerFile")) {
        checkArgs(i + 1, args.length);
        replicationFactorPerFile = Short.parseShort(args[++i]);
      } else if (args[i].equals("-baseDir")) {
        checkArgs(i + 1, args.length);
        baseDir = args[++i];
      } else if (args[i].equals("-readFileAfterOpen")) {
        checkArgs(i + 1, args.length);
        readFileAfterOpen = Boolean.parseBoolean(args[++i]);
      } else if (args[i].equals("-help")) {
        displayUsage();
        isHelpMessage = true;
      }
    }
    
    LOG.info("Test Inputs: ");
    LOG.info("           Test Operation: " + operation);
    LOG.info("               Start time: " + sdf.format(new Date(startTime)));
    LOG.info("           Number of maps: " + numberOfMaps);
    LOG.info("        Number of reduces: " + numberOfReduces);
    LOG.info("               Block Size: " + blockSize);
    LOG.info("           Bytes to write: " + bytesToWrite);
    LOG.info("       Bytes per checksum: " + bytesPerChecksum);
    LOG.info("          Number of files: " + numberOfFiles);
    LOG.info("       Replication factor: " + replicationFactorPerFile);
    LOG.info("                 Base dir: " + baseDir);
    LOG.info("     Read file after open: " + readFileAfterOpen);
    
    // Set user-defined parameters, so the map method can access the values
    getConf().set("test.nnbench.operation", operation);
    getConf().setLong("test.nnbench.maps", numberOfMaps);
    getConf().setLong("test.nnbench.reduces", numberOfReduces);
    getConf().setLong("test.nnbench.starttime", startTime);
    getConf().setLong("test.nnbench.blocksize", blockSize);
    getConf().setInt("test.nnbench.bytestowrite", bytesToWrite);
    getConf().setLong("test.nnbench.bytesperchecksum", bytesPerChecksum);
    getConf().setLong("test.nnbench.numberoffiles", numberOfFiles);
    getConf().setInt("test.nnbench.replicationfactor",
            (int) replicationFactorPerFile);
    getConf().set("test.nnbench.basedir", baseDir);
    getConf().setBoolean("test.nnbench.readFileAfterOpen", readFileAfterOpen);

    getConf().set("test.nnbench.datadir.name", DATA_DIR_NAME);
    getConf().set("test.nnbench.outputdir.name", OUTPUT_DIR_NAME);
    getConf().set("test.nnbench.controldir.name", CONTROL_DIR_NAME);
  }
  
  /**
   * Analyze the results
   * @throws IOException on error
   */
  private int analyzeResults() throws IOException {
    final FileSystem fs = FileSystem.get(new Path(baseDir).toUri(), getConf());
    Path reduceDir = new Path(baseDir, OUTPUT_DIR_NAME);

    long totalTimeAL1 = 0l;
    long totalTimeAL2 = 0l;
    long totalTimeTPmS = 0l;
    long lateMaps = 0l;
    long numOfExceptions = 0l;
    long successfulFileOps = 0l;
    
    long mapStartTimeTPmS = 0l;
    long mapEndTimeTPmS = 0l;

    FileStatus[] fss = fs.listStatus(reduceDir);
    for (FileStatus status : fss) {

      Path reduceFile = status.getPath();
      try (DataInputStream in = new DataInputStream(fs.open(reduceFile));
          BufferedReader lines =
              new BufferedReader(new InputStreamReader(in))) {

        String line;
        while ((line = lines.readLine()) != null) {
          StringTokenizer tokens = new StringTokenizer(line, " \t\n\r\f%;");
          String attr = tokens.nextToken();
          if (attr.endsWith(":totalTimeAL1")) {
            totalTimeAL1 = Long.parseLong(tokens.nextToken());
          } else if (attr.endsWith(":totalTimeAL2")) {
            totalTimeAL2 = Long.parseLong(tokens.nextToken());
          } else if (attr.endsWith(":totalTimeTPmS")) {
            totalTimeTPmS = Long.parseLong(tokens.nextToken());
          } else if (attr.endsWith(":latemaps")) {
            lateMaps = Long.parseLong(tokens.nextToken());
          } else if (attr.endsWith(":numOfExceptions")) {
            numOfExceptions = Long.parseLong(tokens.nextToken());
          } else if (attr.endsWith(":successfulFileOps")) {
            successfulFileOps = Long.parseLong(tokens.nextToken());
          } else if (attr.endsWith(":mapStartTimeTPmS")) {
            mapStartTimeTPmS = Long.parseLong(tokens.nextToken());
          } else if (attr.endsWith(":mapEndTimeTPmS")) {
            mapEndTimeTPmS = Long.parseLong(tokens.nextToken());
          }
        }
      }
    }
    
    // Average latency is the average time to perform 'n' number of
    // operations, n being the number of files
    double avgLatency1 = (double) totalTimeAL1 / successfulFileOps;
    double avgLatency2 = (double) totalTimeAL2 / successfulFileOps;
    
    // The time it takes for the longest running map is measured. Using that,
    // cluster transactions per second is calculated. It includes time to 
    // retry any of the failed operations
    double longestMapTimeTPmS = (double) (mapEndTimeTPmS - mapStartTimeTPmS);
    double totalTimeTPS = (longestMapTimeTPmS == 0) ?
            (1000 * successfulFileOps) :
            (double) (1000 * successfulFileOps) / longestMapTimeTPmS;
            
    // The time it takes to perform 'n' operations is calculated (in ms),
    // n being the number of files. Using that time, the average execution 
    // time is calculated. It includes time to retry any of the
    // failed operations
    double AverageExecutionTime = (totalTimeTPmS == 0) ?
        (double) successfulFileOps : 
        (double) totalTimeTPmS / successfulFileOps;
            
    String resultTPSLine1 = null;
    String resultTPSLine2 = null;
    String resultALLine1 = null;
    String resultALLine2 = null;

    if (operation.equals(OP_CREATE_WRITE)) {
      // For create/write/close, it is treated as two transactions,
      // since a file create from a client perspective involves create and close
      resultTPSLine1 = "               TPS: Create/Write/Close: " + 
        (int) (totalTimeTPS * 2);
      resultTPSLine2 = "Avg exec time (ms): Create/Write/Close: " +
        AverageExecutionTime;
      resultALLine1 = "            Avg Lat (ms): Create/Write: " + avgLatency1;
      resultALLine2 = "                   Avg Lat (ms): Close: " + avgLatency2;
    } else if (operation.equals(OP_OPEN_READ)) {
      resultTPSLine1 = "                        TPS: Open/Read: " + 
        (int) totalTimeTPS;
      resultTPSLine2 = "         Avg Exec time (ms): Open/Read: " + 
        AverageExecutionTime;
      resultALLine1 = "                    Avg Lat (ms): Open: " + avgLatency1;
      if (readFileAfterOpen) {
        resultALLine2 = "                  Avg Lat (ms): Read: " + avgLatency2;
      }
    } else if (operation.equals(OP_RENAME)) {
      resultTPSLine1 = "                           TPS: Rename: " + 
        (int) totalTimeTPS;
      resultTPSLine2 = "            Avg Exec time (ms): Rename: " + 
        AverageExecutionTime;
      resultALLine1 = "                  Avg Lat (ms): Rename: " + avgLatency1;
    } else if (operation.equals(OP_DELETE)) {
      resultTPSLine1 = "                           TPS: Delete: " + 
        (int) totalTimeTPS;
      resultTPSLine2 = "            Avg Exec time (ms): Delete: " + 
        AverageExecutionTime;
      resultALLine1 = "                  Avg Lat (ms): Delete: " + avgLatency1;
    }
    
    String resultLines[] = {
    "-------------- NNBench -------------- : ",
    "                               Version: " + NNBENCH_VERSION,
    "                           Date & time: " + sdf.format(new Date(
            System.currentTimeMillis())),
    "",
    "                        Test Operation: " + operation,
    "                            Start time: " + 
      sdf.format(new Date(startTime)),
    "                           Maps to run: " + numberOfMaps,
    "                        Reduces to run: " + numberOfReduces,
    "                    Block Size (bytes): " + blockSize,
    "                        Bytes to write: " + bytesToWrite,
    "                    Bytes per checksum: " + bytesPerChecksum,
    "                       Number of files: " + numberOfFiles,
    "                    Replication factor: " + replicationFactorPerFile,
    "            Successful file operations: " + successfulFileOps,
    "",
    "        # maps that missed the barrier: " + lateMaps,
    "                          # exceptions: " + numOfExceptions,
    "",
    resultTPSLine1,
    resultTPSLine2,
    resultALLine1,
    resultALLine2,
    "",
    "                 RAW DATA: AL Total #1: " + totalTimeAL1,
    "                 RAW DATA: AL Total #2: " + totalTimeAL2,
    "              RAW DATA: TPS Total (ms): " + totalTimeTPmS,
    "       RAW DATA: Longest Map Time (ms): " + longestMapTimeTPmS,
    "                   RAW DATA: Late maps: " + lateMaps,
    "             RAW DATA: # of exceptions: " + numOfExceptions,
    "" };

    try (PrintStream res = new PrintStream(
        new FileOutputStream(new File(DEFAULT_RES_FILE_NAME), true))) {
      // Write to a file and also dump to log
      for (String resultLine : resultLines) {
        LOG.info(resultLine);
        res.println(resultLine);
      }
    }
    if(numOfExceptions >= MAX_OPERATION_EXCEPTIONS){
      return -1;
    }
    return 0;
  }

  /**
   * Run the test
   * 
   * @throws IOException on error
   */
  private void runTests() throws IOException {
    getConf().setLong("io.bytes.per.checksum", bytesPerChecksum);
    
    JobConf job = new JobConf(getConf(), NNBench.class);

    job.setJobName("NNBench-" + operation);
    FileInputFormat.setInputPaths(job, new Path(baseDir, CONTROL_DIR_NAME));
    job.setInputFormat(SequenceFileInputFormat.class);
    
    // Explicitly set number of max map attempts to 1.
    job.setMaxMapAttempts(1);
    
    // Explicitly turn off speculative execution
    job.setSpeculativeExecution(false);

    job.setMapperClass(NNBenchMapper.class);
    job.setReducerClass(NNBenchReducer.class);

    FileOutputFormat.setOutputPath(job, new Path(baseDir, OUTPUT_DIR_NAME));
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(Text.class);
    job.setNumReduceTasks((int) numberOfReduces);
    JobClient.runJob(job);
  }
  
  /**
   * Validate the inputs
   */
  private void validateInputs() {
    // If it is not one of the four operations, then fail
    if (!operation.equals(OP_CREATE_WRITE) &&
            !operation.equals(OP_OPEN_READ) &&
            !operation.equals(OP_RENAME) &&
            !operation.equals(OP_DELETE)) {
      System.err.println("Error: Unknown operation: " + operation);
      displayUsage();
      throw new HadoopIllegalArgumentException(
          "Error: Unknown operation: " + operation);
    }
    
    // If number of maps is a negative number, then fail
    // Hadoop allows the number of maps to be 0
    if (numberOfMaps < 0) {
      System.err.println("Error: Number of maps must be a positive number");
      displayUsage();
      throw new HadoopIllegalArgumentException(
          "Error: Number of maps must be a positive number");
    }
    
    // If number of reduces is a negative number or 0, then fail
    if (numberOfReduces <= 0) {
      System.err.println("Error: Number of reduces must be a positive number");
      displayUsage();
      throw new HadoopIllegalArgumentException(
          "Error: Number of reduces must be a positive number");
    }

    // If blocksize is a negative number or 0, then fail
    if (blockSize <= 0) {
      System.err.println("Error: Block size must be a positive number");
      displayUsage();
      throw new HadoopIllegalArgumentException(
          "Error: Block size must be a positive number");
    }
    
    // If bytes to write is a negative number, then fail
    if (bytesToWrite < 0) {
      System.err.println("Error: Bytes to write must be a positive number");
      displayUsage();
      throw new HadoopIllegalArgumentException(
          "Error: Bytes to write must be a positive number");
    }
    
    // If bytes per checksum is a negative number, then fail
    if (bytesPerChecksum < 0) {
      System.err.println("Error: Bytes per checksum must be a positive number");
      displayUsage();
      throw new HadoopIllegalArgumentException(
          "Error: Bytes per checksum must be a positive number");
    }
    
    // If number of files is a negative number, then fail
    if (numberOfFiles < 0) {
      System.err.println("Error: Number of files must be a positive number");
      displayUsage();
      throw new HadoopIllegalArgumentException(
          "Error: Number of files must be a positive number");
    }
    
    // If replication factor is a negative number, then fail
    if (replicationFactorPerFile < 0) {
      System.err.println("Error: Replication factor must be a positive number");
      displayUsage();
      throw new HadoopIllegalArgumentException(
          "Error: Replication factor must be a positive number");
    }
    
    // If block size is not a multiple of bytesperchecksum, fail
    if (blockSize % bytesPerChecksum != 0) {
      System.err.println("Error: Block Size in bytes must be a multiple of "
          + "bytes per checksum: ");
      displayUsage();
      throw new HadoopIllegalArgumentException(
          "Error: Block Size in bytes must be a multiple of "
              + "bytes per checksum:");
    }
  }
  /**
  * Main method for running the NNBench benchmarks
  *
  * @param args array of command line arguments
  * @throws IOException indicates a problem with test startup
  */
  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new NNBench(), args);
    System.exit(res);
  }

  @Override
  public int run(String[] args) throws Exception {
    // Display the application version string
    displayVersion();

    // Parse the inputs
    parseInputs(args);
    if (isHelpMessage) {
      return 0;
    }

    // Validate inputs
    validateInputs();
    
    // Clean up files before the test run
    cleanupBeforeTestrun();
    
    // Create control files before test run
    createControlFiles();

    // Run the tests as a map reduce job
    runTests();
    
    // Analyze results
    return analyzeResults();
  }

  
  /**
   * Mapper class
   */
  static class NNBenchMapper extends Configured
          implements Mapper<Text, LongWritable, Text, Text> {
    FileSystem filesystem = null;

    long numberOfFiles = 1l;
    long blkSize = 1l;
    short replFactor = 1;
    int bytesToWrite = 0;
    String baseDir = null;
    String dataDirName = null;
    String op = null;
    boolean readFile = false;
    
    // Data to collect from the operation
    int numOfExceptions = 0;
    long startTimeAL = 0l;
    long totalTimeAL1 = 0l;
    long totalTimeAL2 = 0l;
    long successfulFileOps = 0l;
    
    /**
     * Constructor
     */
    public NNBenchMapper() {
    }
    
    /**
     * Mapper base implementation
     */
    public void configure(JobConf conf) {
      setConf(conf);

      try {
        String dir = conf.get("test.nnbench.basedir");
        filesystem = FileSystem.get(new Path(dir).toUri(), conf);
      } catch(Exception e) {
        throw new RuntimeException("Cannot get file system.", e);
      }
    }
    
    /**
     * Mapper base implementation
     */
    public void close() throws IOException {
    }

    /**
     * Returns when the current number of seconds from the epoch equals
     * the command line argument given by <code>-startTime</code>.
     * This allows multiple instances of this program, running on clock
     * synchronized nodes, to start at roughly the same time.
     * @return true if the method was able to sleep for <code>-startTime</code>
     * without interruption; false otherwise
     */
    private boolean barrier() {
      long startTime = getConf().getLong("test.nnbench.starttime", 0l);
      long currentTime = System.currentTimeMillis();
      long sleepTime = startTime - currentTime;
      boolean retVal = true;
      
      // If the sleep time is greater than 0, then sleep and return
      if (sleepTime > 0) {
        LOG.info("Waiting in barrier for: " + sleepTime + " ms");
      
        try {
          Thread.sleep(sleepTime);
          retVal = true;
        } catch (Exception e) {
          retVal = false;
        }
      }
      
      return retVal;
    }
    
    /**
     * Map method
     */ 
    public void map(Text key, 
            LongWritable value,
            OutputCollector<Text, Text> output,
            Reporter reporter) throws IOException {
      Configuration conf = getConf();
      
      numberOfFiles = conf.getLong("test.nnbench.numberoffiles", 1l);
      blkSize = conf.getLong("test.nnbench.blocksize", 1l);
      replFactor = (short) (conf.getInt("test.nnbench.replicationfactor", 1));
      bytesToWrite = conf.getInt("test.nnbench.bytestowrite", 0);
      baseDir = conf.get("test.nnbench.basedir");
      dataDirName = conf.get("test.nnbench.datadir.name");
      op = conf.get("test.nnbench.operation");
      readFile = conf.getBoolean("test.nnbench.readFileAfterOpen", false);
      
      long totalTimeTPmS = 0l;
      long startTimeTPmS = 0l;
      long endTimeTPms = 0l;
      
      numOfExceptions = 0;
      startTimeAL = 0l;
      totalTimeAL1 = 0l;
      totalTimeAL2 = 0l;
      successfulFileOps = 0l;
      
      if (barrier()) {
        String fileName = "file_" + value;
        if (op.equals(OP_CREATE_WRITE)) {
          startTimeTPmS = System.currentTimeMillis();
          doCreateWriteOp(fileName, reporter);
        } else if (op.equals(OP_OPEN_READ)) {
          startTimeTPmS = System.currentTimeMillis();
          doOpenReadOp(fileName, reporter);
        } else if (op.equals(OP_RENAME)) {
          startTimeTPmS = System.currentTimeMillis();
          doRenameOp(fileName, reporter);
        } else if (op.equals(OP_DELETE)) {
          startTimeTPmS = System.currentTimeMillis();
          doDeleteOp(fileName, reporter);
        } else {
          throw new IllegalArgumentException(
              "unsupported operation [" + op + "]");
        }
        
        endTimeTPms = System.currentTimeMillis();
        totalTimeTPmS = endTimeTPms - startTimeTPmS;
      } else {
        output.collect(new Text("l:latemaps"), new Text("1"));
      }
      
      // collect after the map end time is measured
      output.collect(new Text("l:totalTimeAL1"), 
          new Text(String.valueOf(totalTimeAL1)));
      output.collect(new Text("l:totalTimeAL2"), 
          new Text(String.valueOf(totalTimeAL2)));
      output.collect(new Text("l:numOfExceptions"), 
          new Text(String.valueOf(numOfExceptions)));
      output.collect(new Text("l:successfulFileOps"), 
          new Text(String.valueOf(successfulFileOps)));
      output.collect(new Text("l:totalTimeTPmS"), 
              new Text(String.valueOf(totalTimeTPmS)));
      output.collect(new Text("min:mapStartTimeTPmS"), 
          new Text(String.valueOf(startTimeTPmS)));
      output.collect(new Text("max:mapEndTimeTPmS"), 
          new Text(String.valueOf(endTimeTPms)));
    }
    
    /**
     * Create and Write operation.
     * @param name of the prefix of the putput file to be created
     * @param reporter an instanse of (@link Reporter) to be used for
     *   status' updates
     */
    private void doCreateWriteOp(String name,
                                 Reporter reporter) {
      FSDataOutputStream out;
      byte[] buffer = new byte[bytesToWrite];
      
      for (long l = 0l; l < numberOfFiles; l++) {
        Path filePath = new Path(new Path(baseDir, dataDirName), 
                name + "_" + l);

        boolean successfulOp = false;
        while (! successfulOp && numOfExceptions < MAX_OPERATION_EXCEPTIONS) {
          try {
            // Set up timer for measuring AL (transaction #1)
            startTimeAL = System.currentTimeMillis();
            // Create the file
            // Use a buffer size of 512
            out = filesystem.create(filePath, 
                    true, 
                    512, 
                    replFactor, 
                    blkSize);
            out.write(buffer);
            totalTimeAL1 += (System.currentTimeMillis() - startTimeAL);

            // Close the file / file output stream
            // Set up timers for measuring AL (transaction #2)
            startTimeAL = System.currentTimeMillis();
            out.close();
            
            totalTimeAL2 += (System.currentTimeMillis() - startTimeAL);
            successfulOp = true;
            successfulFileOps ++;

            reporter.setStatus("Finish "+ l + " files");
          } catch (IOException e) {
            LOG.error("Exception recorded in op: Create/Write/Close, "
                + "file: \"" + filePath + "\"", e);
            numOfExceptions++;
          }
        }
      }
    }
    
    /**
     * Open operation
     * @param name of the prefix of the putput file to be read
     * @param reporter an instanse of (@link Reporter) to be used for
     *   status' updates
     */
    private void doOpenReadOp(String name,
                              Reporter reporter) {
      FSDataInputStream input;
      byte[] buffer = new byte[bytesToWrite];
      
      for (long l = 0l; l < numberOfFiles; l++) {
        Path filePath = new Path(new Path(baseDir, dataDirName), 
                name + "_" + l);

        boolean successfulOp = false;
        while (! successfulOp && numOfExceptions < MAX_OPERATION_EXCEPTIONS) {
          try {
            // Set up timer for measuring AL
            startTimeAL = System.currentTimeMillis();
            input = filesystem.open(filePath);
            totalTimeAL1 += (System.currentTimeMillis() - startTimeAL);
            
            // If the file needs to be read (specified at command line)
            if (readFile) {
              startTimeAL = System.currentTimeMillis();
              input.readFully(buffer);

              totalTimeAL2 += (System.currentTimeMillis() - startTimeAL);
            }
            input.close();
            successfulOp = true;
            successfulFileOps ++;

            reporter.setStatus("Finish "+ l + " files");
          } catch (IOException e) {
            LOG.error("Exception recorded in op: OpenRead, " + "file: \""
                + filePath + "\"", e);
            numOfExceptions++;
          }
        }
      }
    }
    
    /**
     * Rename operation
     * @param name of prefix of the file to be renamed
     * @param reporter an instanse of (@link Reporter) to be used for
     *   status' updates
     */
    private void doRenameOp(String name,
                            Reporter reporter) {
      for (long l = 0l; l < numberOfFiles; l++) {
        Path filePath = new Path(new Path(baseDir, dataDirName), 
                name + "_" + l);
        Path filePathR = new Path(new Path(baseDir, dataDirName), 
                name + "_r_" + l);

        boolean successfulOp = false;
        while (! successfulOp && numOfExceptions < MAX_OPERATION_EXCEPTIONS) {
          try {
            // Set up timer for measuring AL
            startTimeAL = System.currentTimeMillis();
            boolean result = filesystem.rename(filePath, filePathR);
            if (!result) {
              throw new IOException("rename failed for " + filePath);
            }
            totalTimeAL1 += (System.currentTimeMillis() - startTimeAL);
            
            successfulOp = true;
            successfulFileOps ++;

            reporter.setStatus("Finish "+ l + " files");
          } catch (IOException e) {
            LOG.error("Exception recorded in op: Rename, " + "file: \""
                + filePath + "\"", e);
            numOfExceptions++;
          }
        }
      }
    }
    
    /**
     * Delete operation
     * @param name of prefix of the file to be deleted
     * @param reporter an instanse of (@link Reporter) to be used for
     *   status' updates
     */
    private void doDeleteOp(String name,
                            Reporter reporter) {
      for (long l = 0l; l < numberOfFiles; l++) {
        Path filePath = new Path(new Path(baseDir, dataDirName), 
                name + "_" + l);
        
        boolean successfulOp = false;
        while (! successfulOp && numOfExceptions < MAX_OPERATION_EXCEPTIONS) {
          try {
            // Set up timer for measuring AL
            startTimeAL = System.currentTimeMillis();
            boolean result = filesystem.delete(filePath, true);
            if (!result) {
              throw new IOException("delete failed for " + filePath);
            }
            totalTimeAL1 += (System.currentTimeMillis() - startTimeAL);
            
            successfulOp = true;
            successfulFileOps ++;

            reporter.setStatus("Finish "+ l + " files");
          } catch (IOException e) {
            LOG.error("Exception recorded in op: Delete, " + "file: \""
                + filePath + "\"", e);
            numOfExceptions++;
          }
        }
      }
    }
  }
  
  /**
   * Reducer class
   */
  static class NNBenchReducer extends MapReduceBase
      implements Reducer<Text, Text, Text, Text> {

    protected String hostName;

    public NNBenchReducer () {
      LOG.info("Starting NNBenchReducer !!!");
      try {
        hostName = java.net.InetAddress.getLocalHost().getHostName();
      } catch(Exception e) {
        hostName = "localhost";
      }
      LOG.info("Starting NNBenchReducer on " + hostName);
    }

    /**
     * Reduce method
     */
    public void reduce(Text key, 
                       Iterator<Text> values,
                       OutputCollector<Text, Text> output, 
                       Reporter reporter
                       ) throws IOException {
      String field = key.toString();
      
      reporter.setStatus("starting " + field + " ::host = " + hostName);
      
      // sum long values
      if (field.startsWith("l:")) {
        long lSum = 0;
        while (values.hasNext()) {
          lSum += Long.parseLong(values.next().toString());
        }
        output.collect(key, new Text(String.valueOf(lSum)));
      }
      
      if (field.startsWith("min:")) {
        long minVal = -1;
        while (values.hasNext()) {
          long value = Long.parseLong(values.next().toString());
          
          if (minVal == -1) {
            minVal = value;
          } else {
            if (value != 0 && value < minVal) {
              minVal = value;
            }
          }
        }
        output.collect(key, new Text(String.valueOf(minVal)));
      }
      
      if (field.startsWith("max:")) {
        long maxVal = -1;
        while (values.hasNext()) {
          long value = Long.parseLong(values.next().toString());
          
          if (maxVal == -1) {
            maxVal = value;
          } else {
            if (value > maxVal) {
              maxVal = value;
            }
          }
        }
        output.collect(key, new Text(String.valueOf(maxVal)));
      }
      
      reporter.setStatus("finished " + field + " ::host = " + hostName);
    }
  }
}
