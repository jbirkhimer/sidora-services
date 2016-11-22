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

package edu.si.services.sidora.rest.batch.beans;

import org.apache.camel.*;
import org.apache.commons.io.FileUtils;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author jbirkhimer
 */
public class BatchRequestControllerBean {

    private static final Logger LOG = LoggerFactory.getLogger(BatchRequestControllerBean.class);

    private Message out;
    private String correlationId;

    /**
     *
     * @param exchange
     * @return
     */
    public void createCorrelationId(Exchange exchange) {

        out = exchange.getIn();

        correlationId = UUID.randomUUID().toString();

        out.setHeader("correlationId", correlationId);
    }

    /**
     *
     * @param exchange
     * @throws Exception
     */
    public void processBatchRequest(Exchange exchange) throws Exception {

        out = exchange.getIn();

        Map<String, Object> batchRequestMap = (Map<String, Object>) out.getBody();

        LOG.debug("processBatchRequest - batchRequestMap MAP: {}", batchRequestMap);

        correlationId = batchRequestMap.get("correlationId").toString();

        out.setBody(correlationId);

        out.setHeader("correlationId", correlationId);
        out.setHeader("parentId", batchRequestMap.get("parentId").toString());
        out.setHeader("resourceCount", batchRequestMap.get("resourceCount").toString());
        out.setHeader("resourceOwner", batchRequestMap.get("resourceOwner").toString());

        //Stash the metadata datastream and sidora datastream to a header
        out.setHeader("ds_metadata", batchRequestMap.get("ds_metadata").toString());
        out.setHeader("ds_sidora", batchRequestMap.get("ds_sidora").toString());
        out.setHeader("association", batchRequestMap.get("association").toString());

        //Header is not null if resource is a csv for codebook
        if (batchRequestMap.get("codebookPID") != null) {
            out.setHeader("codebookPID", batchRequestMap.get("codebookPID").toString());
        }
    }

    /**
     *
     * @param exchange
     * @return
     */
    public Map<String, Object> updateCreatedStatus(Exchange exchange, String datastream) {
        
        out = exchange.getIn();
        correlationId = out.getHeader("correlationId", String.class);
        String resourceFile = out.getHeader("resourceFile", String.class);
        String pid = out.getHeader("CamelFedoraPid", String.class);
        String titleField = out.getHeader("titleField", String.class);
        String contentModel = out.getHeader("contentModel", String.class);

        Map<String, Object> updateCreatedStatus = new HashMap<String, Object>();
        updateCreatedStatus.put("correlationId", correlationId);
        updateCreatedStatus.put("resourceFile", resourceFile);
        updateCreatedStatus.put("pid", pid);
        updateCreatedStatus.put("titleField", titleField);
        updateCreatedStatus.put("contentModel", contentModel);

        updateCreatedStatus.put(datastream, checkStatusCode(out.getHeader("CamelHttpResponseCode", Integer.class)));

        return updateCreatedStatus;
    }

    public Map<String, Object> checkStatus(@Header("correlationId") String correlationId,
                                                       @Header("parentId") String parentId) {

        Map<String, Object> batchRequestStatus = new HashMap<String, Object>();
        batchRequestStatus.put("correlationId", correlationId);
        batchRequestStatus.put("parentId", parentId);

        return batchRequestStatus;

    }

    public void getMIMEType(Exchange exchange) throws URISyntaxException, MalformedURLException {

        /**
         * TODO:
         *
         * Need to make sure that mimetypes are consistent with what's used in workbench.
         * See link for workbench mimetype list
         *
         * https://github.com/Smithsonian/sidora-workbench/blob/master/workbench/includes/utils.inc#L1119
         *
         */

        out = exchange.getIn();

        URL url = new URL(out.getHeader("resourceFile", String.class));

        URI uri = new URI(url.getProtocol(), url.getUserInfo(), url.getHost(), url.getPort(), url.getPath(), url.getQuery(), url.getRef());

        String resourceFile = uri.toASCIIString();

        LOG.debug("Checking {} for MIME Type", resourceFile);

        String mimeType = null;

        mimeType = new Tika().detect(resourceFile);

        LOG.debug("Batch Process " + resourceFile + " || MIME=" + mimeType);

        out.setHeader("dsMIME", mimeType);
    }

    private boolean checkStatusCode(Integer camelHttpResponseCode) {
        if (camelHttpResponseCode != 200 || camelHttpResponseCode != 201) {
            return true;
        } else {
            return false;
        }
    }

}
