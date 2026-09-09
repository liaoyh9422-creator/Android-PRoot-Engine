package com.android.proot.sample.service;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

public class IFlowIpcDispatcherTest {

    @Test
    public void testHandleJsonRequest_nullOrEmpty() {
        IFlowIpcDispatcher dispatcher = IFlowIpcDispatcher.getInstance(null);
        String respNull = dispatcher.handleJsonRequest(null);
        Assert.assertTrue(respNull.contains("\"ok\":false"));

        String respEmpty = dispatcher.handleJsonRequest("   ");
        Assert.assertTrue(respEmpty.contains("\"ok\":false"));
    }

    @Test
    public void testHandleJsonRequest_unknownAction() throws Exception {
        IFlowIpcDispatcher dispatcher = IFlowIpcDispatcher.getInstance(null);
        String resp = dispatcher.handleJsonRequest("{\"action\":\"unknown_foo_bar\"}");
        JSONObject json = new JSONObject(resp);
        Assert.assertFalse(json.getBoolean("ok"));
        Assert.assertTrue(json.getString("error").contains("Unknown action"));
    }

    @Test
    public void testHandleJsonRequest_emptyExecCommand() throws Exception {
        IFlowIpcDispatcher dispatcher = IFlowIpcDispatcher.getInstance(null);
        String resp = dispatcher.handleJsonRequest("{\"action\":\"exec\",\"command\":\"\"}");
        JSONObject json = new JSONObject(resp);
        Assert.assertFalse(json.getBoolean("ok"));
        Assert.assertTrue(json.getString("output").contains("Error: Empty command"));
    }

    @Test
    public void testHandleJsonRequest_portsAction() throws Exception {
        IFlowIpcDispatcher dispatcher = IFlowIpcDispatcher.getInstance(null);
        String resp = dispatcher.handleJsonRequest("{\"action\":\"ports\"}");
        JSONObject json = new JSONObject(resp);
        Assert.assertTrue(json.getBoolean("ok"));
        Assert.assertTrue(json.has("ssh_port"));
        Assert.assertTrue(json.has("proxy_port"));
        Assert.assertTrue(json.has("web_port"));
    }

    @Test
    public void testHandleJsonRequest_invalidJsonFormat() {
        IFlowIpcDispatcher dispatcher = IFlowIpcDispatcher.getInstance(null);
        String resp = dispatcher.handleJsonRequest("{not-a-valid-json");
        Assert.assertTrue(resp.contains("\"ok\":false"));
    }

    @Test
    public void testHandleJsonRequest_workspaceAction() throws Exception {
        IFlowIpcDispatcher dispatcher = IFlowIpcDispatcher.getInstance(null);
        String resp = dispatcher.handleJsonRequest("{\"action\":\"workspace\"}");
        JSONObject json = new JSONObject(resp);
        Assert.assertTrue(json.getBoolean("ok"));
        Assert.assertTrue(json.has("workspace"));
    }

    @Test
    public void testSetActiveWorkspace_validation() {
        IFlowIpcDispatcher dispatcher = IFlowIpcDispatcher.getInstance(null);
        Assert.assertFalse(dispatcher.setActiveWorkspace(null));
        Assert.assertFalse(dispatcher.setActiveWorkspace(""));
        Assert.assertFalse(dispatcher.setActiveWorkspace("relative/path"));
    }

    @Test
    public void testExecuteCommand_emptyValidation() {
        IFlowIpcDispatcher dispatcher = IFlowIpcDispatcher.getInstance(null);
        String out1 = dispatcher.executeCommand(null, null, 1000);
        Assert.assertTrue(out1.startsWith("Error:"));

        String out2 = dispatcher.executeCommand("   ", null, 1000);
        Assert.assertTrue(out2.startsWith("Error:"));
    }

    @Test
    public void testControlService_invalid() {
        IFlowIpcDispatcher dispatcher = IFlowIpcDispatcher.getInstance(null);
        Assert.assertFalse(dispatcher.controlService(null, null));
        Assert.assertFalse(dispatcher.controlService("unknown_srv", "start"));
    }
}
