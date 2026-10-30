/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.rest.action.admin.cluster;

import org.opensearch.OpenSearchParseException;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionType;
import org.opensearch.action.admin.cluster.wlm.WlmStatsAction;
import org.opensearch.action.admin.cluster.wlm.WlmStatsResponse;
import org.opensearch.action.pagination.PageToken;
import org.opensearch.action.pagination.WlmPaginationStrategy;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.Table;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.rest.BytesRestResponse;
import org.opensearch.rest.RestChannel;
import org.opensearch.rest.RestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.RestResponse;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.client.NoOpNodeClient;
import org.opensearch.test.rest.FakeRestChannel;
import org.opensearch.test.rest.FakeRestRequest;
import org.opensearch.transport.client.node.NodeClient;
import org.opensearch.wlm.ResourceType;
import org.opensearch.wlm.stats.SortBy;
import org.opensearch.wlm.stats.SortOrder;
import org.opensearch.wlm.stats.WlmStats;
import org.opensearch.wlm.stats.WorkloadGroupStats;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RestWlmStatsActionTests extends OpenSearchTestCase {

    private RestWlmStatsAction action;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        action = new RestWlmStatsAction();
    }

    public void testParsePageSizeDefault() {
        RestRequest request = mock(RestRequest.class);
        when(request.paramAsInt("size", 10)).thenReturn(10);
        assertEquals(10, action.parsePageSize(request));
    }

    public void testParsePageSizeValid() {
        RestRequest request = mock(RestRequest.class);
        when(request.paramAsInt("size", 10)).thenReturn(25);
        assertEquals(25, action.parsePageSize(request));
    }

    public void testParsePageSizeNegative() {
        RestRequest request = mock(RestRequest.class);
        when(request.paramAsInt("size", 10)).thenReturn(-5);
        expectThrows(OpenSearchParseException.class, () -> action.parsePageSize(request));
    }

    public void testParsePageSizeTooLarge() {
        RestRequest request = mock(RestRequest.class);
        when(request.paramAsInt("size", 10)).thenReturn(102);
        expectThrows(OpenSearchParseException.class, () -> action.parsePageSize(request));
    }

    public void testParseSortByValid() {
        assertEquals(SortBy.NODE_ID, action.parseSortBy("node_id"));
        assertEquals(SortBy.WORKLOAD_GROUP, action.parseSortBy("workload_group"));
    }

    public void testParseSortByInvalid() {
        expectThrows(OpenSearchParseException.class, () -> action.parseSortBy("invalid_key"));
    }

    public void testParseSortOrderValid() {
        assertEquals(SortOrder.ASC, action.parseSortOrder("asc"));
        assertEquals(SortOrder.DESC, action.parseSortOrder("desc"));
    }

    public void testParseSortOrderInvalid() {
        expectThrows(OpenSearchParseException.class, () -> action.parseSortOrder("upside_down"));
    }

    public void testCreateTableWithHeaders() {
        PageToken token = new PageToken("a", "b");
        Table table = action.createTableWithHeaders(token, true, true);
        assertNotNull(table);
    }

    public void testAddRow() {
        Table table = action.createTableWithHeaders(null, true, true);
        WorkloadGroupStats.WorkloadGroupStatsHolder stats = new WorkloadGroupStats.WorkloadGroupStatsHolder(
            1,
            2,
            3,
            4,
            5,
            6,
            new HashMap<>()
        );
        action.addRow(table, "node1", "group1", stats, true);
        assertEquals(1, table.getRows().size());
    }

    public void testAddFooterRow() {
        Table table = action.createTableWithHeaders(null, true, true);
        // Derive the width from the headers rather than hardcoding it, so adding a column cannot silently desync.
        action.addFooterRow(table, table.getHeaders().size());
        assertEquals(1, table.getRows().size());
    }

    public void testBuildTable() {
        Table table = action.createTableWithHeaders(null, false, true);

        WorkloadGroupStats.ResourceStats cpuStats = new WorkloadGroupStats.ResourceStats(0.5, 2, 8);
        WorkloadGroupStats.ResourceStats memoryStats = new WorkloadGroupStats.ResourceStats(0.6, 1, 9);
        Map<ResourceType, WorkloadGroupStats.ResourceStats> statsMap = new HashMap<>();
        statsMap.put(ResourceType.CPU, cpuStats);
        statsMap.put(ResourceType.MEMORY, memoryStats);

        WorkloadGroupStats.WorkloadGroupStatsHolder statsHolder = new WorkloadGroupStats.WorkloadGroupStatsHolder(
            1,
            2,
            3,
            4,
            5,
            6,
            statsMap
        );
        Map<String, WorkloadGroupStats.WorkloadGroupStatsHolder> groupStats = new HashMap<>();
        groupStats.put("groupA", statsHolder);
        WorkloadGroupStats stats = new WorkloadGroupStats(groupStats);

        DiscoveryNode mockNode = mock(DiscoveryNode.class);
        when(mockNode.getId()).thenReturn("nodeX");

        WlmStats wlmStats = new WlmStats(mockNode, stats);
        List<WlmStats> statsList = Collections.singletonList(wlmStats);

        ClusterName clusterName = new ClusterName("test-cluster");
        WlmStatsResponse response = new WlmStatsResponse(clusterName, statsList, List.of());

        WlmPaginationStrategy strategy = new WlmPaginationStrategy(10, null, SortBy.NODE_ID, SortOrder.ASC, response);
        action.buildTable(table, statsList, strategy, true);

        assertEquals(2, table.getRows().size());
    }

    public void testHandlePaginationError() throws IOException {
        RestChannel channel = mock(RestChannel.class);
        BytesRestResponse[] capturedResponse = new BytesRestResponse[1];
        doAnswer(invocation -> {
            capturedResponse[0] = invocation.getArgument(0);
            return null;
        }).when(channel).sendResponse(any());

        action.handlePaginationError(
            channel,
            "bad_token",
            10,
            SortBy.NODE_ID,
            SortOrder.ASC,
            new OpenSearchParseException("forced failure")
        );

        assertNotNull(capturedResponse[0]);
        assertEquals(RestStatus.BAD_REQUEST, capturedResponse[0].status());
    }

    public void testGetName() {
        assertEquals("wlm_stats_action", action.getName());
    }

    public void testPrepareRequestTabular() throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("nodeId", "nodeA,nodeB");
        params.put("workloadGroupId", "group1,group2");
        params.put("breach", "true");
        params.put("boolean", "true");
        params.put("sort", "workload_group");
        params.put("order", "desc");
        params.put("next_token", "token123");
        params.put("size", "5");

        FakeRestRequest request = new FakeRestRequest.Builder(xContentRegistry()).withMethod(RestRequest.Method.GET)
            .withPath("/_list/wlm_stats")
            .withParams(params)
            .build();

        NodeClient client = mock(NodeClient.class);
        var consumer = action.prepareRequest(request, client);
        assertNotNull(consumer);
    }

    public void testRoutes() {
        RestWlmStatsAction action = new RestWlmStatsAction();

        List<RestHandler.Route> routes = action.routes();
        assertNotNull(routes);
        assertEquals(8, routes.size());

        // Convert to a set of "method path" strings for easier matching
        Set<String> actualRoutes = routes.stream().map(route -> route.getMethod() + " " + route.getPath()).collect(Collectors.toSet());

        Set<String> expectedRoutes = Set.of(
            "GET _wlm/stats",
            "GET _wlm/{nodeId}/stats",
            "GET _wlm/stats/{workloadGroupId}",
            "GET _wlm/{nodeId}/stats/{workloadGroupId}",
            "GET _list/wlm_stats",
            "GET _list/wlm_stats/{nodeId}/stats",
            "GET _list/wlm_stats/stats/{workloadGroupId}",
            "GET _list/wlm_stats/{nodeId}/stats/{workloadGroupId}"
        );

        assertEquals(expectedRoutes, actualRoutes);
    }

    public void testStructuredListResponse() throws Exception {
        assertStructuredListResponse(false);
    }

    public void testPaginatedStructuredListResponse() throws Exception {
        assertStructuredListResponse(true);
    }

    private void assertStructuredListResponse(boolean paginated) throws Exception {
        for (XContentType type : XContentType.values()) {
            for (boolean useFormat : new boolean[] { true, false }) {
                Map<String, String> params = new HashMap<>();
                if (paginated) {
                    params.put("size", "1");
                }
                if (useFormat) {
                    params.put("format", type.format());
                }
                // An explicit format must take precedence over Accept.
                Map<String, List<String>> headers = Map.of("Accept", List.of(useFormat ? "text/plain" : type.mediaType()));
                RestResponse response = listResponse(params, headers);
                assertEquals(type.mediaType(), response.contentType());
                try (XContentParser parser = createParser(type.xContent(), response.content())) {
                    if (paginated) {
                        assertEquals(XContentParser.Token.START_OBJECT, parser.nextToken());
                        assertEquals(XContentParser.Token.FIELD_NAME, parser.nextToken());
                        assertEquals("next_token", parser.currentName());
                        assertEquals(XContentParser.Token.VALUE_STRING, parser.nextToken());
                        assertFalse(parser.text().isEmpty());
                        assertEquals(XContentParser.Token.FIELD_NAME, parser.nextToken());
                        assertEquals("wlm_stats", parser.currentName());
                    }
                    assertEquals(XContentParser.Token.START_ARRAY, parser.nextToken());
                    assertStatsRow(parser, "groupA");
                    if (paginated == false) {
                        assertStatsRow(parser, "groupB");
                        assertRow(parser, List.of("No more pages available", "-", "-", "-", "-", "-", "-", "-", "-"));
                    }
                    assertEquals(XContentParser.Token.END_ARRAY, parser.nextToken());
                    if (paginated) {
                        assertEquals(XContentParser.Token.END_OBJECT, parser.nextToken());
                    }
                    assertNull(parser.nextToken());
                }
            }
        }
    }

    private void assertStatsRow(XContentParser parser, String group) throws IOException {
        assertRow(parser, List.of("node1", group, "1", "2", "4", "5", "6", "0.5", "0.6"));
    }

    private void assertRow(XContentParser parser, List<String> values) throws IOException {
        List<String> fields = List.of(
            "NODE_ID",
            "WORKLOAD_GROUP_ID",
            "TOTAL_COMPLETIONS",
            "TOTAL_REJECTIONS",
            "TOTAL_CANCELLATIONS",
            "TOTAL_THROTTLED",
            "TOTAL_WOULD_THROTTLE",
            "CPU_USAGE",
            "MEMORY_USAGE"
        );
        assertEquals(XContentParser.Token.START_OBJECT, parser.nextToken());
        // Check every member as a token so duplicate fields cannot be silently overwritten by a map.
        for (int i = 0; i < fields.size(); i++) {
            assertEquals(XContentParser.Token.FIELD_NAME, parser.nextToken());
            assertEquals(fields.get(i), parser.currentName());
            assertEquals(XContentParser.Token.VALUE_STRING, parser.nextToken());
            assertEquals(values.get(i), parser.text());
        }
        assertEquals(XContentParser.Token.END_OBJECT, parser.nextToken());
    }

    public void testTextListResponseKeepsSeparators() throws Exception {
        for (boolean paginated : new boolean[] { true, false }) {
            for (int negotiation = 0; negotiation < 4; negotiation++) {
                Map<String, String> params = new HashMap<>();
                params.put("v", "true");
                if (paginated) {
                    params.put("size", "1");
                }
                Map<String, List<String>> headers = Map.of();
                if (negotiation == 1) {
                    params.put("format", "text");
                    headers = Map.of("Accept", List.of("application/json"));
                } else if (negotiation == 2) {
                    headers = Map.of("Accept", List.of("text/plain"));
                } else if (negotiation == 3) {
                    headers = Map.of("Content-Type", List.of("application/json"));
                }
                RestResponse response = listResponse(params, headers);
                assertEquals("text/plain; charset=UTF-8", response.contentType());
                String[] lines = response.content().utf8ToString().split("\n");
                assertEquals(8, lines[0].chars().filter(c -> c == '|').count());
                assertEquals("node1 | groupA | 1 | 2 | 4 | 5 | 6 | 0.5 | 0.6", lines[1].trim().replaceAll("\\s+", " "));
                if (paginated) {
                    assertTrue(lines[2].startsWith("next_token "));
                } else {
                    assertEquals("node1 | groupB | 1 | 2 | 4 | 5 | 6 | 0.5 | 0.6", lines[2].trim().replaceAll("\\s+", " "));
                    assertTrue(lines[3].startsWith("No more pages available"));
                }
            }
        }
    }

    private RestResponse listResponse(Map<String, String> params, Map<String, List<String>> headers) throws Exception {
        WorkloadGroupStats.WorkloadGroupStatsHolder stats = new WorkloadGroupStats.WorkloadGroupStatsHolder(
            1,
            2,
            3,
            4,
            5,
            6,
            Map.of(
                ResourceType.CPU,
                new WorkloadGroupStats.ResourceStats(0.5, 2, 8),
                ResourceType.MEMORY,
                new WorkloadGroupStats.ResourceStats(0.6, 1, 9)
            )
        );
        DiscoveryNode node = mock(DiscoveryNode.class);
        when(node.getId()).thenReturn("node1");
        WlmStatsResponse statsResponse = new WlmStatsResponse(
            new ClusterName("test-cluster"),
            List.of(new WlmStats(node, new WorkloadGroupStats(Map.of("groupA", stats, "groupB", stats)))),
            List.of()
        );
        FakeRestRequest request = new FakeRestRequest.Builder(xContentRegistry()).withMethod(RestRequest.Method.GET)
            .withPath("/_list/wlm_stats")
            .withParams(params)
            .withHeaders(headers)
            .build();
        FakeRestChannel channel = new FakeRestChannel(request, true, 1);
        try (NodeClient client = new NoOpNodeClient(getTestName()) {
            @Override
            @SuppressWarnings("unchecked")
            public <Request extends ActionRequest, Response extends ActionResponse> void doExecute(
                ActionType<Response> actionType,
                Request actionRequest,
                ActionListener<Response> listener
            ) {
                assertSame(WlmStatsAction.INSTANCE, actionType);
                listener.onResponse((Response) statsResponse);
            }
        }) {
            action.handleRequest(request, channel, client);
        }
        RestResponse response = channel.capturedResponse();
        assertNotNull(response);
        assertEquals(RestStatus.OK, response.status());
        return response;
    }
}
