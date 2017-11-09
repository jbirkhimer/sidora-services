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

package edu.si.services.itest;

import edu.si.services.beans.cameratrap.CT_BlueprintTestSupport;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.AdviceWithRouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.model.LogDefinition;
import org.apache.http.HttpStatus;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.custommonkey.xmlunit.XMLUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.apache.commons.io.FileUtils.readFileToString;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.apache.http.auth.AuthScope.ANY;
import static org.custommonkey.xmlunit.XMLAssert.assertXMLEqual;

/**
 * Testing Project and Subproject findObject logic using alternate id's to check if an object exists
 * and falling back to checking using string name. The test assumes neither Project or SubProject exist
 * and will continue to create the object, However actual object creation is intercepted or sent to mock endpoints.
 *
 * @author jbirkhimer
 */
public class UCT_AltId_IT extends CT_BlueprintTestSupport {

    private static String LOG_NAME = "edu.si.mci";

    private static final boolean USE_ACTUAL_FEDORA_SERVER = true;
    private String defaultTestProperties = KARAF_HOME + "/test.properties";

    private static final File testManifest = new File("src/test/resources/AltIdSampleData/Unified/deployment_manifest.xml");
    private static final File projectRELS_EXT = new File("src/test/resources/AltIdSampleData/Unified/projectRELS-EXT.rdf");
    private static final File subProjectRELS_EXT = new File("src/test/resources/AltIdSampleData/Unified/subProjectRELS-EXT.rdf");
    private static final File objectNotFoundFusekiResponse = new File("src/test/resources/AltIdSampleData/objectNotFoundFusekiResponse.xml");

    protected static final String FEDORA_URI = System.getProperty("si.fedora.host");
    protected static final String FUSEKI_URI = System.getProperty("si.fuseki.host") + "/fedora3";
    protected static final String FITS_URI = System.getProperty("si.fits.host");
    private static final String KARAF_HOME = System.getProperty("karaf.home");
    private static final String FOXML = KARAF_HOME + "/foxml";

    private static CloseableHttpClient httpClient;

    private static final Logger logger = LoggerFactory.getLogger(UCT_AltId_IT.class);

    @Override
    protected String getBlueprintDescriptor() {
        return "Routes/unified-camera-trap-route.xml";
    }

    @Override
    protected List<String> loadAdditionalPropertyFiles() {
        return Arrays.asList(KARAF_HOME + "/etc/edu.si.sidora.karaf.cfg", KARAF_HOME + "/etc/system.properties");
    }

    @Override
    protected String[] preventRoutesFromStarting() {
        return new String[]{"UnifiedCameraTrapInFlightConceptStatusPolling"};
    }

    @Override
    public void setUp() throws Exception {
        setUseActualFedoraServer(USE_ACTUAL_FEDORA_SERVER);
        setFedoraServer(FEDORA_URI, System.getProperty("si.fedora.user"), System.getProperty("si.fedora.password"));
        setFuseki(FUSEKI_URI);
        setDefaultTestProperties(defaultTestProperties);
        super.setUp();
    }

    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    @BeforeClass
    public static void loadObjectsIntoFedora() throws IOException {
        BasicCredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(ANY, new UsernamePasswordCredentials(System.getProperty("si.fedora.user"), System.getProperty("si.fedora.password")));
        httpClient = HttpClientBuilder.create().setDefaultCredentialsProvider(provider).build();
        ingest("si:121909", Paths.get(FOXML, "eMammal.xml"));
    }

    private static void ingest(String pid, Path payload) throws IOException {
        if (!checkObjecsExist(pid)) {
            HttpPost ingest = new HttpPost(FEDORA_URI + "/objects/" + pid + "?format=info:fedora/fedora-system:FOXML-1.1");
            ingest.setEntity(new ByteArrayEntity(Files.readAllBytes(payload)));
            ingest.setHeader("Content-type", MediaType.TEXT_XML);
            try (CloseableHttpResponse pidRes = httpClient.execute(ingest)) {
                assertEquals("Failed to ingest " + pid + "!", SC_CREATED, pidRes.getStatusLine().getStatusCode());
                logger.info("Ingested test object {}", EntityUtils.toString(pidRes.getEntity()));
            }
        }
    }

    private static boolean checkObjecsExist(String pid) throws IOException {
        String query = URLEncoder.encode("ASK FROM <info:edu.si.fedora#ri> WHERE { <info:fedora/" + pid + "> ?p ?o}", "UTF-8");

        HttpGet request = new HttpGet(FUSEKI_URI + "?output=xml&query=" + query);
        final boolean exists;
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
            String pidExists = EntityUtils.toString(response.getEntity()).split("<boolean>")[1].split("</boolean>")[0];
            exists = Boolean.parseBoolean(pidExists);
            logger.info("PID = {}, Exists = {}", pid, exists);
        }

        return exists;
    }

    @AfterClass
    public static void cleanUpHttpClient() throws IOException {
        httpClient.close();
    }

    @Test
    public void testFedora() throws IOException {
        HttpGet request = new HttpGet(FEDORA_URI + "/objects/si:121909/datastreams/DC/content?format=xml");
        final String xml;
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
            xml = EntityUtils.toString(response.getEntity());
        }
        logger.info("Found DC datastream profile:\n{}", xml);
        String expectedProfile = "\n<oai_dc:dc xmlns:oai_dc=\"http://www.openarchives.org/OAI/2.0/oai_dc/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.openarchives.org/OAI/2.0/oai_dc/ http://www.openarchives.org/OAI/2.0/oai_dc.xsd\">\n" +
                "  <dc:title>eMammal</dc:title>\n" +
                "  <dc:title>eMammal</dc:title>\n" +
                "  <dc:description></dc:description>\n" +
                "  <dc:identifier>si:121909</dc:identifier>\n" +
                "  <dc:relation></dc:relation>\n" +
                "</oai_dc:dc>\n";
        assertEquals(expectedProfile, xml);
    }

    @Test
    public void testFuseki() throws IOException {
        String parentPid = "si:121909";

        String testQuery = URLEncoder.encode("ASK FROM <info:edu.si.fedora#ri> { <info:fedora/" + parentPid + "> ?p ?o .}", "UTF-8");

        logger.info("testQuery={}", testQuery);

        HttpGet request = new HttpGet(FUSEKI_URI + "?output=xml&query=" + testQuery);
        final String xml;
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
            xml = EntityUtils.toString(response.getEntity());
        }
        logger.info("Found datastream profile:\n{}", xml);
        String expectedProfile = "<?xml version=\"1.0\"?>\n" +
                "<sparql xmlns=\"http://www.w3.org/2005/sparql-results#\">\n" +
                "  <head>\n" +
                "  </head>\n" +
                "  <boolean>true</boolean>\n" +
                "</sparql>\n";
        assertEquals(expectedProfile, xml);
    }

    /*@Test
    public void testFits() throws IOException {
        HttpGet request = new HttpGet(FITS_URI + "/version");
        final String xml;
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertEquals(HttpStatus.SC_OK, response.getStatusLine().getStatusCode());
            xml = EntityUtils.toString(response.getEntity());
        }
        logger.info("Found FITS Version:{}", xml);
        String expectedVersion = "1.0.4";
        assertEquals(expectedVersion, xml);
    }*/

    /**
     * Testing Project and Subproject datastreams created and have alternate id's. The test assumes neither Project or SubProject exist
     * and will continue to create the object.
     *
     * @throws Exception
     */
    @Test
    public void processParentsTest() throws Exception {

        //The mock endpoint we are sending to for assertions
        MockEndpoint mockResult = getMockEndpoint("mock:mockResult");
        mockResult.expectedMessageCount(6);

        /* Advicewith the routes as needed for this test */
        context.getRouteDefinition("UnifiedCameraTrapProcessParents").adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                interceptSendToEndpoint("direct:processPlot").skipSendToOriginalEndpoint().log(LoggingLevel.INFO, "Skipping processPlot");

                //Grab the Datastreams from fedora and send then to the mock endpoint for assertion
                weaveByType(LogDefinition.class).selectLast().after()
                        //Project Datastreams
                        .toD("fcrepo:objects/${headers.ProjectPID}/datastreams?format=xml").id("mockResult1")
                        .convertBodyTo(String.class)
                        .to("mock:mockResult")
                        .toD("fcrepo:objects/${headers.ProjectPID}/datastreams/DC/content?format=xml").id("mockResult2")
                        .convertBodyTo(String.class)
                        .log("Project DC=\n${body}")
                        .to("mock:mockResult")
                        .toD("fcrepo:objects/${headers.ProjectPID}/datastreams/RELS-EXT/content?format=xml").id("mockResult3")
                        .convertBodyTo(String.class)
                        .log("Project RELS-EXT=\n${body}")
                        .to("mock:mockResult")

                        //SubProject datastreams
                        .toD("fcrepo:objects/${headers.SubProjectPID}/datastreams?format=xml").id("mockResult4")
                        .convertBodyTo(String.class)
                        .to("mock:mockResult")
                        .toD("fcrepo:objects/${headers.SubProjectPID}/datastreams/DC/content?format=xml").id("mockResult5")
                        .convertBodyTo(String.class)
                        .log("Sub Project DC=\n${body}")
                        .to("mock:mockResult")
                        .toD("fcrepo:objects/${headers.SubProjectPID}/datastreams/RELS-EXT/content?format=xml").id("mockResult6")
                        .convertBodyTo(String.class)
                        .log("Sub Project RELS-EXT=\n${body}")
                        .to("mock:mockResult");
            }
        });

        context.start();

        //Initialize the exchange with body and headers as needed
        Exchange exchange = new DefaultExchange(context);
        exchange.getIn().setHeader("ManifestXML", readFileToString(testManifest));
        exchange.getIn().setHeader("CamelFileParent", "someCamelFileParent");
        exchange.getIn().setHeader("CamelFedoraPid", getConfig().getString("si.ct.root"));

        // The endpoint we want to start from with the exchange body and headers we want
        template.send("direct:processParents", exchange);

        //Delay test so we can open fedora admin to look at the objects
        //Thread.sleep(300000);

        List<String> resultList = new ArrayList<String>();
        for (Exchange resultExchange : mockResult.getExchanges()) {
            resultList.add(resultExchange.getIn().getBody(String.class));
        }

        XMLUnit.setIgnoreWhitespace(true);
        XMLUnit.setIgnoreComments(true);

        //Assert Project DatastreamsUCT_AltId_IT
        assertXMLEqual(readFileToString(new File(KARAF_HOME + "/AltIdSampleData/Unified/projectDatastreams.xml")), resultList.get(0));
        assertXMLEqual(readFileToString(new File(KARAF_HOME + "/AltIdSampleData/Unified/projectDC.xml")), resultList.get(1));
        assertXMLEqual(readFileToString(new File(KARAF_HOME + "/AltIdSampleData/Unified/projectRELS-EXT.rdf")), resultList.get(2));

        //Assert Subproject Datastreams
        assertXMLEqual(readFileToString(new File(KARAF_HOME + "/AltIdSampleData/Unified/subprojectDatastreams.xml")), resultList.get(3));
        assertXMLEqual(readFileToString(new File(KARAF_HOME + "/AltIdSampleData/Unified/subprojectDC.xml")), resultList.get(4));
        assertXMLEqual(readFileToString(new File(KARAF_HOME + "/AltIdSampleData/Unified/subProjectRELS-EXT.rdf")), resultList.get(5));

        assertMockEndpointsSatisfied();
    }

    /**
     * Testing Project and Subproject findObject logic using alternate id's to check if an object exists
     * and falling back to checking using string name.
     *
     * The test assumes Project and SubProject with altId exist.
     *
     * @throws Exception
     */
    @Test
    public void altIDExistsTest() throws Exception {

        //The mock endpoint we are sending to for assertions
        MockEndpoint mockProjectResult = getMockEndpoint("mock:mockProjectResult");
        mockProjectResult.expectedMessageCount(6);
        mockProjectResult.expectedHeaderReceived("findObjectBy", "AltId");

        MockEndpoint mockSubprojectResult = getMockEndpoint("mock:mockSubprojectResult");
        mockSubprojectResult.expectedMessageCount(6);
        mockSubprojectResult.expectedHeaderReceived("findObjectBy", "Name");

        MockEndpoint mockProjectDC = getMockEndpoint("mock:projectDCResult");
        mockProjectDC.expectedMessageCount(1);
        //mockProjectDC.expectedBodiesReceived(readFileToString(projectDC));

        MockEndpoint mockSubprojectDC = getMockEndpoint("mock:subprojectDCResult");
        mockSubprojectDC.expectedMessageCount(1);
        //mockSubprojectDC.expectedBodiesReceived(readFileToString(subProjectDC));

        /* Advicewith the routes as needed for this test */
        context.getRouteDefinition("UnifiedCameraTrapProcessParents").adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                interceptSendToEndpoint("direct:processPlot").skipSendToOriginalEndpoint().log(LoggingLevel.INFO, "Skipping processPlot");
            }
        });

        context.getRouteDefinition("UnifiedCameraTrapProcessProject").adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                // Intercept sending DC datastream to Fedora and send to mock for assertion
                weaveById("addProjectDC").before()
                        .log(LoggingLevel.INFO, "Sending Project DC created to mock endpoint")
                        .to("mock:projectDCResult");
            }
        });

        context.getRouteDefinition("UnifiedCameraTrapProcessSubproject").adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                //intercept sending DC datastream to Fedora and send to mock for assertion
                weaveById("addSubprojectDC").before()
                        .log(LoggingLevel.INFO, "Sending Subproject DC created to mock endpoint")
                        .setHeader("routeId", simple("${routeId}"))
                        .to("mock:subprojectDCResult");
            }
        });

        context.getRouteDefinition("UnifiedCameraTrapFindObject").adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                //Send to mock:result for assertion
                weaveByType(LogDefinition.class).selectFirst().before()
                        .choice()
                        .when(simple("${header.findObjectBy} == 'AltId'")).to("mock:mockProjectResult")
                        .otherwise().to("mockSubprojectResult");
            }
        });

        context.start();

        //Initialize the exchange with body and headers as needed
        Exchange exchange = new DefaultExchange(context);
        exchange.getIn().setHeader("ManifestXML", readFileToString(testManifest));
        exchange.getIn().setHeader("CamelFileParent", "someCamelFileParent");
        exchange.getIn().setHeader("CamelFedoraPid", getConfig().getString("si.ct.root"));

        // The endpoint we want to start from with the exchange body and headers we want
        template.send("direct:processParents", exchange);

        //Delay test so we can open fedora admin to look at the objects
        //Thread.sleep(300000);



        XMLUnit.setIgnoreWhitespace(true);
        XMLUnit.setIgnoreComments(true);

        //Assert Project DC
        assertXMLEqual(readFileToString(new File(KARAF_HOME + "/AltIdSampleData/Unified/projectDC.xml")), mockProjectDC.getExchanges().get(0).getIn().getBody(String.class));

        //Assert Subproject DC
        assertXMLEqual(readFileToString(new File(KARAF_HOME + "/AltIdSampleData/Unified/subprojectDC.xml")), mockSubprojectDC.getExchanges().get(0).getIn().getBody(String.class));

        assertMockEndpointsSatisfied();
    }
}
