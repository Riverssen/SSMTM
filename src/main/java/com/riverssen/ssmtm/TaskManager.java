package com.riverssen.ssmtm;

import java.util.List;
import java.util.Queue;
import java.util.Set;

public interface TaskManager extends Runnable
{
    void Setup(int connectionlimit, int port, SSMCallBack<Peer> disconnectionCallback, RPCRuntime rpcEnvironment);
    /**
     * @param message
     *
     * This will broadcast the message to all connected peers.
     */
    void SendMessage(final Message message);
    /**
     * @param message
     * @param peer
     *
     * This will send a message to a specific peer.
     */
    void SendMessage(final Message message, final Peer peer);
    /**
     * @param peer
     *
     * Attempt to connect to a peer.
     */
    boolean ForceConnect(final Peer peer, int port);
    boolean ForceDisconnect(final Peer peer);
    boolean IsConnected(final Peer peer);
    void SendMessageToAllExcept(final Message message, final Peer nosend);
    void BlockPeer(final Peer peer);
    Set<Peer> GetConnected();
    List<Message> GetMessages();
    void RegisterCommand(final int command, final CommandExecutor runnable);
    void AbortOperations();
}