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
package org.apache.vxquery.hdfs2;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.mapred.SplitLocationInfo;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.util.ReflectionUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.hdfs.ContextFactory;
import edu.uci.ics.hyracks.hdfs2.dataflow.FileSplitsFactory;

import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

public class HDFSFunctions {

    private Configuration conf;
    private FileSystem fs;
    private String conf_path;
    private Job job;
    private InputFormat inputFormat;
    private List<InputSplit> splits;
    private ArrayList<ArrayList<String>> nodes;
    private File nodeXMLfile;
    private HashMap<Integer, String> schedule;

    /**
     * Create the configuration and add the paths for core-site and hdfs-site as resources.
     * Initialize an instance of HDFS FileSystem for this configuration.
     */
    public HDFSFunctions() {
        this.conf = new Configuration();
    }

    /**
     * Create the needed objects for reading the splits of the filepath given as argument.
     * This method should run before the scheduleSplits method.
     * 
     * @param filepath
     */
    @SuppressWarnings({ "deprecation", "unchecked" })
    public void setJob(String filepath, String tag) {
        try {
            conf.set("start_tag", "<" + tag + ">");
            conf.set("end_tag", "</" + tag + ">");
            job = new Job(conf, "Read from HDFS");
            Path input = new Path(filepath);
            FileInputFormat.addInputPath(job, input);
            //TODO change input format class to XMLInputFormatClassOneBufferSolution
            job.setInputFormatClass(XmlCollectionByTagInputFormat.class);
            inputFormat = ReflectionUtils.newInstance(job.getInputFormatClass(), job.getConfiguration());
            splits = inputFormat.getSplits(job);
        } catch (IOException e) {
            System.err.println(e);
        } catch (ClassNotFoundException e) {
            System.err.println(e);
        } catch (InterruptedException e) {
            System.err.println(e);
        }
    }

    /**
     * Returns true if the file path exists or it is located somewhere in the home directory of the user that called the function.
     * Searches in subdirectories of the home directory too.
     * 
     * @param filename
     * @return
     */
    public boolean isLocatedInHDFS(String filename) {
        try {
            //search file path
            if (fs.exists(new Path(filename))) {
                return true;
            }
        } catch (IOException ex) {
            System.err.println(ex);
        }
        return searchInDirectory(fs.getHomeDirectory(), filename) != null;
    }

    /**
     * Searches the given directory for the file.
     * 
     * @param directory
     *            to search
     * @param filename
     *            of file we want
     * @return path if file exists in this directory.else return null.
     */
    public Path searchInDirectory(Path directory, String filename) {
        //Search the files and folder in this Path to find the one matching the filename.
        try {
            RemoteIterator<LocatedFileStatus> it = fs.listFiles(directory, true);
            String[] parts;
            Path path;
            while (it.hasNext()) {
                path = it.next().getPath();
                parts = path.toString().split("/");
                if (parts[parts.length - 1].equals(filename)) {
                    return path;
                }
            }
        } catch (IOException ex) {
            System.err.println(ex);
        }
        return null;
    }

    /**
     * Read the cluster properties file and locate the HDFS_CONF variable that is the directory path for the
     * hdfs configuration if the system environment variable HDFS_CONF is not set.
     * 
     * @return true if is successfully finds the Hadoop/HDFS home directory
     */
    private boolean locateConf() {
        this.conf_path = System.getenv("HADOOP_CONF_DIR");
        System.out.println(conf_path);
        if (this.conf_path == null) {
            
            // load properties file
            Properties prop = new Properties();
            String propFilePath = "../vxquery-server/src/main/resources/conf/cluster.properties";
            try {
                prop.load(new FileInputStream(propFilePath));
            } catch (FileNotFoundException e) {
                propFilePath = "vxquery-server/src/main/resources/conf/cluster.properties";
                try {
                    prop.load(new FileInputStream(propFilePath));
                } catch (FileNotFoundException e1) {
                } catch (IOException e1) {
                }
            } catch (IOException e) {
                System.err.println(e);
                return false;
            }

            // get the property value for HDFS_CONF
            this.conf_path = prop.getProperty("HDFS_CONF");
            System.out.println(conf_path);
            return this.conf_path != null;
        }
        return this.conf_path != null;
    }

    /**
     * Upload a file/directory to HDFS.Filepath is the path in the local file system.dir is the destination path.
     * 
     * @param filepath
     * @param dir
     * @return
     */
    public boolean put(String filepath, String dir) {
        if (this.fs != null) {
            Path path = new Path(filepath);
            Path dest = new Path(dir);
            try {
                if (fs.exists(dest)) {
                    fs.delete(dest, true); //recursive delete
                }
            } catch (IOException e) {
                System.err.println(e);
            }
            try {
                fs.copyFromLocalFile(path, dest);
            } catch (IOException e) {
                System.err.println(e);
            }
        }
        return false;
    }

    /**
     * Get instance of the HDFSfile system if it is configured correctly.
     * Return null if there is no instance.
     * 
     * @return
     */
    public FileSystem getFileSystem() {
        if (locateConf()) {
            conf.addResource(new Path(this.conf_path + "/core-site.xml"));
            conf.addResource(new Path(this.conf_path + "/hdfs-site.xml"));
            try {
                fs = FileSystem.get(conf);
                return this.fs;
            } catch (IOException ex) {
                System.err.println(ex);
            }
        } else {
            System.err.println("Could not locate hdfs configuarion folder.");
        }
        return null;
    }

    /**
     * Create a HashMap that has as key the hostname and values the splits that belong to this hostname;
     * 
     * @return
     * @throws IOException
     */
    public HashMap<String, ArrayList<Integer>> getLocationsOfSplits() throws IOException {
        HashMap<String, ArrayList<Integer>> splits_map = new HashMap<String, ArrayList<Integer>>();
        ArrayList<Integer> temp;
        int i = 0;
        String hostname;
        for (InputSplit s : this.splits) {
            SplitLocationInfo info[] = s.getLocationInfo();
            hostname = info[0].getLocation();
            if (splits_map.containsKey(hostname)) {
                temp = splits_map.get(hostname);
                temp.add(i);
            } else {
                temp = new ArrayList<Integer>();
                temp.add(i);
                splits_map.put(hostname, temp);
            }
            i++;
        }

        return splits_map;
    }

    public void scheduleSplits() throws IOException {

        schedule = new HashMap<Integer, String>();
        ArrayList<String> empty = new ArrayList<String>();
        HashMap<String, ArrayList<Integer>> splits_map = this.getLocationsOfSplits();
        readNodesFromXML();
        int count = this.splits.size();

        ArrayList<Integer> splits;
        String node;
        for (ArrayList<String> info : this.nodes) {
            node = info.get(0);
            if (splits_map.containsKey(node)) {
                splits = splits_map.get(node);
                for (Integer split : splits) {
                    schedule.put(split, node);
                    count--;
                }
                splits_map.remove(node);
            } else {
                empty.add(node);
            }
        }

        //Check if every split got assigned to a node
        if (count != 0) {
            ArrayList<Integer> remaining = new ArrayList<Integer>();
            // Find remaining splits
            for (InputSplit s : this.splits) {
                int i = 0;
                if (!schedule.containsKey(i)) {
                    remaining.add(i);
                }
            }

            if (empty.size() != 0) {
                int node_number = 0;
                for (int split : remaining) {
                    if (node_number == empty.size()) {
                        node_number = 0;
                    }
                    schedule.put(split, empty.get(node_number));
                    node_number++;
                }
            }
        }
        // TODO remove from here this is for debugging only
        for (int s : schedule.keySet()) {
            System.out.println("split: " + s + ", host: " + schedule.get(s));
        }
    }

    /**
     * Read the hostname and the ip address of every node from the xml cluster configuration file.
     * Save the information inside an ArrayList.
     */
    public void readNodesFromXML() {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder;
        try {
            dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(nodeXMLfile);
            doc.getDocumentElement().normalize();

            nodes = new ArrayList<ArrayList<String>>();
            NodeList nList = doc.getElementsByTagName("node");

            for (int temp = 0; temp < nList.getLength(); temp++) {

                Node nNode = nList.item(temp);

                if (nNode.getNodeType() == Node.ELEMENT_NODE) {

                    Element eElement = (Element) nNode;
                    ArrayList<String> info = new ArrayList<String>();
                    info.add(eElement.getElementsByTagName("id").item(0).getTextContent());
                    info.add(eElement.getElementsByTagName("cluster_ip").item(0).getTextContent());
                    nodes.add(info);
                }
            }
        } catch (ParserConfigurationException e) {
            System.err.println(e);
        } catch (SAXException e) {
            System.err.println(e);
        } catch (IOException e) {
            System.err.println(e);
        }
    }

    /**
     * Writes the schedule to a temporary file, then uploads the file to the HDFS.
     */
    public void addScheduleToDistributedCache() {
        String filepath = "/tmp/splits_schedule.txt";
        String dfs_path = "vxquery_splits_schedule.txt";
        PrintWriter writer;
        try {
            writer = new PrintWriter(filepath, "UTF-8");
            for (int split : this.schedule.keySet()) {
                writer.write(split + "," + this.schedule.get(split));
            }
            writer.close();
        } catch (FileNotFoundException e) {
            System.err.println(e);
        } catch (UnsupportedEncodingException e) {
            System.err.println(e);
        }
        // Add file to HDFS
        this.put(filepath, dfs_path);
    }

    public RecordReader getReader() {

        List<FileSplit> fileSplits = new ArrayList<FileSplit>();
        for (int i = 0; i < splits.size(); i++) {
            fileSplits.add((FileSplit) splits.get(i));
        }
        FileSplitsFactory splitsFactory;
        try {
            splitsFactory = new FileSplitsFactory(fileSplits);
            List<FileSplit> inputSplits = splitsFactory.getSplits();
            ContextFactory ctxFactory = new ContextFactory();
            int size = inputSplits.size();
            for (int i = 0; i < size; i++) {
                /**
                 * read the split
                 */
                TaskAttemptContext context;
                try {
                    context = ctxFactory.createContext(job.getConfiguration(), i);
                    RecordReader reader = inputFormat.createRecordReader(inputSplits.get(i), context);
                    reader.initialize(inputSplits.get(i), context);
                    return reader;
                } catch (HyracksDataException e) {
                    System.err.println(e);
                } catch (IOException e) {
                    System.err.println(e);
                } catch (InterruptedException e) {
                    System.err.println(e);
                }
            }
        } catch (HyracksDataException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        return null;
    }

    /**
     * @return schedule.
     */
    public HashMap<Integer, String> getSchedule() {
        return this.schedule;
    }

    /**
     * Return the splits belonging to this node for the existing schedule.
     * 
     * @param node
     * @return
     */
    public ArrayList<Integer> getScheduleForNode(String node) {
        ArrayList<Integer> node_schedule = new ArrayList<Integer>();
        for (int split : this.schedule.keySet()) {
            if (node.equals(this.schedule.get(split))) {
                node_schedule.add(split);
            }
        }
        return node_schedule;
    }

    public List<InputSplit> getSplits() {
        return this.splits;
    }

    public Job getJob() {
        return this.job;
    }

    public InputFormat getinputFormat() {
        return this.inputFormat;
    }

    public Document convertStringToDocument(String xmlStr) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlStr)));
            return doc;
        } catch (Exception e) {
            System.err.println(e);
        }
        return null;
    }
}