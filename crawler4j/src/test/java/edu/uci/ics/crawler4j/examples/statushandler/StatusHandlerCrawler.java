/**
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

package edu.uci.ics.crawler4j.examples.statushandler;

import java.util.regex.Pattern;

import org.apache.http.HttpStatus;

import edu.uci.ics.crawler4j.CrawlerConfiguration;
import edu.uci.ics.crawler4j.crawler.*;
import edu.uci.ics.crawler4j.crawler.controller.CrawlController;
import edu.uci.ics.crawler4j.crawler.fetcher.PageFetcher;
import edu.uci.ics.crawler4j.frontier.Frontier;
import edu.uci.ics.crawler4j.frontier.pageharvests.PageHarvests;
import edu.uci.ics.crawler4j.parser.Parser;
import edu.uci.ics.crawler4j.robotstxt.RobotstxtServer;
import edu.uci.ics.crawler4j.url.WebURL;

/**
 * @author Yasser Ganjisaffar
 */
public class StatusHandlerCrawler extends DefaultWebCrawler {

    private static final Pattern FILTERS = Pattern.compile(
            ".*(\\.(css|js|bmp|gif|jpe?g|png|tiff?|mid|mp2|mp3|mp4|wav|avi|mov|mpeg|ram|m4v|pdf"
                    + "|rm|smil|wmv|swf|wma|zip|rar|gz))$");

    public StatusHandlerCrawler(Integer id, CrawlerConfiguration configuration,
            CrawlController controller, PageFetcher pageFetcher, RobotstxtServer robotstxtServer,
            PageHarvests pageHarvests, Frontier frontier, Parser parser) {
        super(id, configuration, controller, pageFetcher, robotstxtServer, pageHarvests, frontier,
                parser);
    }

    /**
     * You should implement this function to specify whether the given url should be crawled or not
     * (based on your crawling logic).
     */
    @Override
    public boolean shouldVisit(Page referringPage, WebURL url) {
        String href = url.getURL().toLowerCase();
        return !FILTERS.matcher(href).matches() && href.startsWith("http://www.ics.uci.edu/");
    }

    /**
     * This function is called when a page is fetched and ready to be processed by your program.
     */
    @Override
    public void visit(Page page) {
        // Do nothing
    }

    @Override
    public void pageFetched(WebURL webUrl, int statusCode, String statusDescription) {
        if (statusCode != HttpStatus.SC_OK) {
            if (statusCode == HttpStatus.SC_NOT_FOUND) {
                logger.warn("Broken link: {}, this link was found in page: {}", webUrl.getURL(),
                        webUrl.getParentURL());
            } else {
                logger.warn("Non success status for link: {} status code: {}, description: ", webUrl
                        .getURL(), statusCode, statusDescription);
            }
        }
    }
}