/*
 * Copyright 2015-2016 Smithsonian Institution.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.You may obtain a copy of
 * the License at: http://www.apache.org/licenses/
 *
 * This software and accompanying documentation is supplied without
 * warranty of any kind. The copyright holder and the Smithsonian Institution:
 * (1) expressly disclaim any warranties, express or implied, including but not
 * limited to any implied warranties of merchantability, fitness for a
 * particular purpose, title or non-infringement; (2) do not assume any legal
 * liability or responsibility for the accuracy, completeness, or usefulness of
 * the software; (3) do not represent that use of the software would not
 * infringe privately owned rights; (4) do not warrant that the software
 * is error-free or will be maintained, supported, updated or enhanced;
 * (5) will not be liable for any indirect, incidental, consequential special
 * or punitive damages of any kind or nature, including but not limited to lost
 * profits or loss of data, on any basis arising from contract, tort or
 * otherwise, even if any of the parties has been warned of the possibility of
 * such loss or damage.
 *
 * This distribution includes several third-party libraries, each with their own
 * license terms. For a complete copy of all copyright and license terms, including
 * those of third-party libraries, please see the product release notes.
 */

package edu.si.services.solr;

//import com.amazonaws.util.json.JSONObject;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.apache.http.auth.AuthScope.ANY;
import static org.junit.Assume.assumeTrue;

/**
 * @author jbirkhimer
 */
public class CheckDeploymentsInSolr {

    private static final Logger log = LoggerFactory.getLogger(CheckDeploymentsInSolr.class);

    protected static String SOLR_SERVER;
    protected static int SOLR_PORT;
    private static final String KARAF_HOME = System.getProperty("karaf.home");
    private static final String PROJECT_BASE_DIR = System.getProperty("baseDir");
    private static Properties props = new Properties();
    private static CloseableHttpClient client;

    private static String hostURL = null;
    private static String sianctIndex = "gsearch_sianct";
    private static String solrIndex = "gsearch_solr";

    @BeforeClass
    public static void loadConfig() throws Exception {
        List<String> propFileList = loadAdditionalPropertyFiles();
        if (loadAdditionalPropertyFiles() != null) {
            for (String propFile : propFileList) {
                Properties extra = new Properties();
                try {
                    extra.load(new FileInputStream(propFile));
                    props.putAll(extra);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        //props.putAll(System.getProperties());

        log.info("Creating Http Client");
        log.debug("using user: {}, password: {}", props.getProperty("si.fedora.user"), props.getProperty("si.fedora.password"));

        BasicCredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(ANY, new UsernamePasswordCredentials(props.getProperty("si.fedora.user"), props.getProperty("si.fedora.password")));
        client = HttpClientBuilder.create().setDefaultCredentialsProvider(provider).build();
    }


    @AfterClass
    public static void cleanUp() throws Exception {
        log.info("Closing Http Client");
        client.close();
    }

    protected static List<String> loadAdditionalPropertyFiles() {
        return Arrays.asList(KARAF_HOME + "/etc/system.properties", KARAF_HOME + "/etc/edu.si.sidora.karaf.cfg", KARAF_HOME + "/etc/edu.si.sidora.solr.cfg", KARAF_HOME + "/etc/test.properties");
    }

    public static boolean hostAvailabilityCheck(String host, int port) {
        log.info("Checking Host Availability: {}", host + ":" + port);
        try (Socket socket = new Socket(new URL(host).getHost(), port)) {
            return true;
        } catch (IOException ex) {
            /* ignore */
        }
        return false;
    }

    public Object doGet(String uri, String fileName) {
        Object entityResponse = null;
        HttpEntity entity = null;
        HttpGet httpMethod = new HttpGet(uri);
        try (CloseableHttpResponse response = client.execute(httpMethod)) {
            entity = response.getEntity();

            Integer responseCode = response.getStatusLine().getStatusCode();
            String statusLine = response.getStatusLine().getReasonPhrase();
            log.debug("content type: {}", entity.getContentType().getValue());
            log.debug("headers: {}", response.getAllHeaders());

            if (entity.getContentType() != null && entity.getContentType().getValue().equals("image/jpeg")) {
                File file = File.createTempFile(fileName + "_", ".jpg");
                file.deleteOnExit();
                if (entity != null) {
                    try (FileOutputStream outstream = new FileOutputStream(file)) {
                        entity.writeTo(outstream);
                    }
                }
                entityResponse = file;
            } else {
                entityResponse = EntityUtils.toString(entity, "UTF-8");
            }
        } catch (Exception e) {
            log.error("doGet() error, {}", e.getMessage());
            e.printStackTrace();
        }

        return entityResponse;
    }

    public <T> T xpath(String source, String expression, QName qName) {
        Object result = null;
        try {
            DocumentBuilderFactory domFactory = DocumentBuilderFactory.newInstance();
            domFactory.setNamespaceAware(true);
            DocumentBuilder builder = domFactory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(source)));
            XPathFactory xPathfactory = XPathFactory.newInstance();
            XPath xpath = xPathfactory.newXPath();
            xpath.setNamespaceContext(new NamespaceContext() {
                @Override
                public String getNamespaceURI(String prefix) {
                    if (prefix == null) {
                        throw new IllegalArgumentException("No prefix provided!");
                    } else if (prefix.equals("fsmgmt")) {
                        return "http://www.fedora.info/definitions/1/0/management/";
                    } else if (prefix.equals("fedora")) {
                        return "info:fedora/fedora-system:def/relations-external#";
                    } else if (prefix.equals("rdf")) {
                        return "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
                    } else if (prefix.equals("ri")) {
                        return "http://www.w3.org/2005/sparql-results#";
                    } else if (prefix.equals("fits")) {
                        return "http://hul.harvard.edu/ois/xml/ns/fits/fits_output";
                    } else {
                        return XMLConstants.NULL_NS_URI;
                    }
                }

                @Override
                public String getPrefix(String namespaceURI) {
                    return null;
                }

                @Override
                public Iterator getPrefixes(String namespaceURI) {
                    return null;
                }
            });
            result = xpath.evaluate(expression, document, qName);
        } catch (Exception e) {
            log.error("xpath() error, {}", e.getMessage());
            e.printStackTrace();
        }
        return (T) result;
    }


    @Test
    public void checkSolrIndexesTest() throws Exception {

        long startTime = System.currentTimeMillis();

        assumeTrue("Must Provide host url!!!", hostURL != null);

        List<String> deploymentList = new ArrayList<>(Arrays.asList("d49470", "d49471", "d49472", "d49674", "d49943", "d49995", "d49996", "d50034", "d36773", "d36774", "d36775", "d36776", "d36777", "d36778", "d36779", "d36780", "d49716", "d49717", "d49718", "d49720", "d49721", "d49722", "d49724", "d49726", "d49729", "d49730", "d49732", "d49929", "d49930", "d49931", "d49932", "d49933", "d49934", "d49935", "d49936", "d49945", "d49960", "d49961", "d47801", "d47802", "d48122", "d49517", "d49208"));
        //List<String> deploymentList = new ArrayList<>(Arrays.asList("d36773")); //small deployment
        //List<String> deploymentList = new ArrayList<>(Arrays.asList("d49676")); //has plot

        /*ThreadPoolExecutor tpes = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
        tpes.setMaximumPoolSize(10);*/
        ExecutorService tpes = Executors.newFixedThreadPool(10);

        for (int i = 0; i < deploymentList.size(); i++) {
            String deployment = deploymentList.get(i);

            String fusekiQuery = URLEncoder.encode("SELECT ?projectPID ?subProjectPID ?plotPID ?deploymentPID FROM <info:edu.si.fedora#ri> WHERE {?deploymentPID <http://purl.org/dc/elements/1.1/identifier> '" + deployment + "' . OPTIONAL {?subProjectPID <info:fedora/fedora-system:def/relations-external#hasConcept> ?deploymentPID ; <info:fedora/fedora-system:def/model#hasModel> <info:fedora/si:projectCModel> } . OPTIONAL {?plotPID <info:fedora/fedora-system:def/relations-external#hasConcept> ?deploymentPID ; <info:fedora/fedora-system:def/model#hasModel> <info:fedora/si:ctPlotCModel> . ?subProjectPID <info:fedora/fedora-system:def/relations-external#hasConcept> ?plotPID } . ?projectPID <info:fedora/fedora-system:def/relations-external#hasConcept> ?subProjectPID .}", "UTF-8");

            String fusekiResult = (String) doGet("http://" + hostURL + ":9080/fuseki/fedora3?output=xml&query=" + fusekiQuery, null);

            //parents
            String projectPID = xpath(fusekiResult, "substring-after(/ri:sparql/ri:results/ri:result[1]/ri:binding[@name = 'projectPID']/ri:uri, 'info:fedora/')", XPathConstants.STRING);
            String subProjectPID = xpath(fusekiResult, "substring-after(/ri:sparql/ri:results/ri:result[1]/ri:binding[@name = 'subProjectPID']/ri:uri, 'info:fedora/')", XPathConstants.STRING);
            String plotPID = xpath(fusekiResult, "substring-after(/ri:sparql/ri:results/ri:result[1]/ri:binding[@name = 'plotPID']/ri:uri, 'info:fedora/')", XPathConstants.STRING);
            String deploymentPID = xpath(fusekiResult, "substring-after(/ri:sparql/ri:results/ri:result[1]/ri:binding[@name = 'deploymentPID']/ri:uri, 'info:fedora/')", XPathConstants.STRING);


            //RELS-EXT resource pid list
            String deploymentRelsExt = (String) doGet("http://" + hostURL + ":8080/fedora/objects/" + deploymentPID + "/datastreams/RELS-EXT/content", null);
            log.debug("deployment RELS-EXT: {}", deploymentRelsExt);
            NodeList deploymentRelsExtResourcePidList = xpath(deploymentRelsExt.trim(), "//fedora:hasResource/@rdf:resource", XPathConstants.NODESET);
            log.debug("RELS_EXT resource count: {}", deploymentRelsExtResourcePidList.getLength());

            List<String> pidList = new ArrayList<>();
            //add parents
            pidList.add(projectPID);
            pidList.add(subProjectPID);
            if (!plotPID.isEmpty()) {
                pidList.add(plotPID);
            }
            pidList.add(deploymentPID);

            //add resources
            for (int j = 0; j < deploymentRelsExtResourcePidList.getLength(); j++) {
                String pid = StringUtils.substringAfter(deploymentRelsExtResourcePidList.item(j).getTextContent(), "info:fedora/");
                log.debug("resourcePid: {}, {}/{}", pid, j + 1, deploymentRelsExtResourcePidList.getLength());
                pidList.add(pid);
            }

            log.debug("deployment resource count: {}", deploymentRelsExtResourcePidList.getLength());

            CountDownLatch countDownLatch = new CountDownLatch(pidList.size());
            long deploymentStartTime = System.currentTimeMillis();

            for (int j = 0; j < pidList.size(); j++) {
                int finalJ = j;
                String pid = pidList.get(j);

                if (finalJ == 0) {
                    if (!plotPID.isEmpty()) {
                        log.info("checking deployment({}/{}): {}, pid count: {}, parents[ project: {}, subProject: {}, plot: {}, deployment: {} ]", deploymentList.indexOf(deployment) + 1, deploymentList.size(), deployment, pidList.size(), projectPID, subProjectPID, plotPID, deploymentPID);
                    } else {
                        log.info("checking deployment({}/{}): {}, pid count: {}, parents[ project: {}, subProject: {} deployment: {} ]", deploymentList.indexOf(deployment) + 1, deploymentList.size(), deployment, pidList.size(), projectPID, subProjectPID, deploymentPID);
                    }
                }
                log.debug("resourcePid: {}, {}/{}", pid, j + 1, pidList.size());

                tpes.submit(new Runnable() {
                    @Override
                    public void run() {
                        boolean isObservation = isObservation(pid);
                        boolean sianctHasPid = false;
                        if (isObservation) {
                            sianctHasPid = checkSolr(pid, sianctIndex);
                        }

                        boolean solrHasPid = checkSolr(pid, solrIndex);

                        if (!isObservation) {
                            if (solrHasPid) {
                                log.debug("deployment: {}, found pid({}/{}): {}, index[ {}: {} ]", deployment, finalJ + 1, pidList.size(), pid, solrIndex,solrHasPid);
                            } else {
                                log.error("{} index is missing pid({}/{}): {}, index[ {}: {} ]", solrIndex, finalJ + 1, pidList.size(), pid, solrIndex, solrHasPid);
                            }
                        } else {
                            if (solrHasPid && sianctHasPid) {
                                log.debug("deployment: {}, found pid({}/{}): {}, index[ {}: {}, {}: {} ]",deployment, finalJ + 1, pidList.size(), pid, solrIndex, solrHasPid, sianctIndex, sianctHasPid);
                            } else if (solrHasPid && !sianctHasPid) {
                                log.error("{} index is missing pid({}/{}): {}, index[ {}: {}, {}: {} ]", sianctIndex, finalJ + 1, pidList.size(), pid, solrIndex, solrHasPid, sianctIndex,  sianctHasPid);
                            } else if (!solrHasPid && sianctHasPid) {
                                log.error("{} index is missing pid({}/{}): {}, index[ {}: {}, {}: {} ]", solrIndex, finalJ + 1, pidList.size(), pid, solrIndex, solrHasPid, sianctIndex,  sianctHasPid);
                            } else {
                                log.error("{} and {} index's are missing pid({}/{}): {}, index[ {}: {}, {}: {} ]", solrIndex, sianctIndex, finalJ + 1, pidList.size(), pid, solrIndex, solrHasPid, sianctIndex,  sianctHasPid);
                            }
                        }
                        countDownLatch.countDown();
                    }
                });
            }
            countDownLatch.await();
            long processingTime = System.currentTimeMillis() - deploymentStartTime;
            log.info("finished deployment({}/{}): {}, pid count: {}, Elapsed Time: {}", deploymentList.indexOf(deployment) + 1, deploymentList.size(), deployment, pidList.size(), String.format("%tT", (processingTime) - TimeZone.getDefault().getRawOffset()));
        }
        long processingTime = System.currentTimeMillis() - startTime;
        log.info("Total Processing Time: {}", String.format("%tT", (processingTime) - TimeZone.getDefault().getRawOffset()));
        tpes.shutdown();
    }

    private boolean isObservation(String pid) {
        String resourceRelsExt = (String) doGet("http://" + hostURL + ":8080/fedora/objects/" + pid + "/datastreams/RELS-EXT/content", null);

        boolean isObservation = xpath(resourceRelsExt, "boolean(//@rdf:resource='info:fedora/si:datasetCModel')", XPathConstants.BOOLEAN);
        return isObservation;
    }

    private boolean checkSolr(String pid, String solrIndex) {
        boolean hasPid = false;
        SOLR_PORT = 8090;
        SOLR_SERVER = "http://" + hostURL + "";
        String uri = SOLR_SERVER + ":" + SOLR_PORT + "/solr/" + solrIndex + "/select";
        String solrQuery = "PID:" + "\"" + pid + "\"";

        try {
            String httpQuery = "?q=" + URLEncoder.encode(solrQuery, "UTF-8") + "&wt=json&indent=true";

            String solrResult = (String) doGet(uri + httpQuery, null);

            JSONObject json = new JSONObject(solrResult);

            int numFound = json.getJSONObject("response").getInt("numFound");
            int count = json.getJSONObject("response").getJSONArray("docs").length();

            if (numFound != 0 || count != 0) {
                String solrPID = json.getJSONObject("response").getJSONArray("docs").getJSONObject(0).getString("PID");
                log.debug("found pid: {}, index: {}, solrPID: {}, numFound: {}, count: {}", pid, solrIndex, solrPID, numFound, count);
                if (pid.equals(solrPID)) {
                    hasPid = true;
                } else {
                    hasPid = false;
                }
            } else {
                hasPid = false;
            }
        } catch (Exception e) {
            log.error("There was and error checking solr, {}", e.getMessage());
            e.printStackTrace();
        }

        return hasPid;
    }
}
