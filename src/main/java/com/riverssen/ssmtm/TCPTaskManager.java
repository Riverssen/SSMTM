package com.riverssen.ssmtm;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TCPTaskManager implements TaskManager
{
    private final Set<Peer>                             mPeers;
    private final Map<byte[], SSMCallBack<Message>>     mMessageCallbacks;
    private final Map<byte[], Message>                  mMessages;
    private final Map<String, TCPPeer>                  mConnections;
    private final Queue<Message>                        mReceivedQueue;
    private Lock                                        mLock;
    private boolean                                     mKeepRunning;
    private int                                         mConnectionLimit;
    private List<Command>                               mCommands;
    private short                                       mPort;

    public TCPTaskManager(final int connectionLimit, short port)
    {
        this.mPeers             = new LinkedHashSet<Peer>();
        this.mMessageCallbacks  = new HashMap<byte[], SSMCallBack<Message>>();
        this.mMessages          = new HashMap<byte[], Message>();
        this.mConnections       = new HashMap<String, TCPPeer>();
        this.mReceivedQueue     = new LinkedList<Message>();
        this.mConnectionLimit   = connectionLimit;
        this.mCommands          = new ArrayList<>();
        this.mPort              = port;
    }

    private void PollMessages()
    {
        for (Peer peer : mPeers)
        {
            TCPPeer pServer = mConnections.get(peer.toString());

            pServer.Poll(mReceivedQueue);
        }
    }

    public void SendMessage(final Message message)
    {
        for (Peer peer : mPeers)
        {
            TCPPeer pServer = mConnections.get(peer.toString());

            pServer.Send(message);

            if (message.GetShouldReply())
            {
                mMessageCallbacks.put(message.GetID(), message.GetCallBack());
                mMessages.put(message.GetID(), message);
                message.GetCallBack().Log();
                message.Send();
            }
        }
    }

    public void SendMessage(final Message message, final Peer peer)
    {
        if (mConnections.containsKey(peer.toString()))
        {
            mConnections.get(peer.toString()).Send(message);

            if (message.GetShouldReply())
            {
                mMessageCallbacks.put(message.GetID(), message.GetCallBack());
                mMessages.put(message.GetID(), message);
                message.GetCallBack().Log();
            }
        }
    }

    public boolean ForceConnect(final Peer peer, int port)
    {
        mLock.lock();
        boolean succeeded = false;

        try{
            if (!mConnections.containsKey(peer.toString()))
            {
                try {
                     Socket socket = new Socket(peer.GetAddress(), port);
                     TCPPeer TCPpeer = new TCPPeer(peer, socket);
//                     TCPpeer.start();

                    ExecutorService service = Executors.newSingleThreadExecutor();
                    service.execute(TCPpeer);

                     mConnections.put(peer.toString(), TCPpeer);
                     mPeers.add(peer);

                    System.out.println("connected to: " + peer);

                     succeeded = true;
                } catch (Exception e)
                {
                }
            }
        } finally
        {
            mLock.unlock();
            return succeeded;
        }
    }

    public void AddConnection(final Peer peer, Socket socket)
    {
        mLock.lock();

        try
        {
            if (!mConnections.containsKey(peer.toString()) && mConnectionLimit > mConnections.size())
            {
                try
                {
                    TCPPeer TCPpeer = new TCPPeer(peer, socket);
//                    TCPpeer.start();

                    ExecutorService service = Executors.newSingleThreadExecutor();
                    service.execute(TCPpeer);

                    mConnections.put(peer.toString(), TCPpeer);
                    mPeers.add(peer);

                    System.out.println("connected to: " + peer);
                } catch (Exception e)
                {
                }
            } else
                socket.close();
        } catch (IOException e)
        {
            e.printStackTrace();
        } finally
        {
            mLock.unlock();
        }
    }

    public boolean ForceDisconnect(final Peer peer)
    {
        mLock.lock();
        boolean succeeded = false;

        try
        {
            if (mConnections.containsKey(peer.toString()))
            {
                mConnections.get(peer.toString()).Abort();
                mPeers.remove(peer);

                succeeded = true;
            }
        } finally
        {
            mLock.unlock();
            return succeeded;
        }
    }

    public Set<Peer> GetConnected()
    {
        return mPeers;
    }

    public Queue<Message> GetMessages()
    {
        return mReceivedQueue;
    }

    @Override
    public void RegisterCommand(final int command, final CommandExecutor executorRunnable)
    {
        mLock.lock();
        try
        {
            this.mCommands.add(new Command(command, executorRunnable));
        } finally
        {
            mLock.unlock();
        }
    }

    public void run()
    {
        mLock = new ReentrantLock();
        mKeepRunning = true;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            ServerSocket mServerSocket = null;

            try
            {
                mServerSocket = new ServerSocket(mPort);
            } catch (IOException e)
            {
                e.printStackTrace();
            }

            if (mServerSocket == null)
                mKeepRunning = false;

            while (mKeepRunning)
            {
                try
                {
                    Socket socket = mServerSocket.accept();
                    Peer peer = new Peer(socket.getInetAddress());

                    AddConnection(peer, socket);
                } catch (IOException e)
                {
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e)
                {
                }
            }
        });

        while (mKeepRunning)
        {
            mLock.lock();
            try
            {
                PollMessages();


                List<Integer> toRemove = new ArrayList<Integer>();

                for (int i = 0; i < mReceivedQueue.size(); i ++)
                {
                    Message message = ((LinkedList<Message>) mReceivedQueue).get(i);

                    if (message.GetType() == MessageType.DISCONNECT)
                        ForceDisconnect(message.GetPeer());
                    else if (message.GetType() == MessageType.PING)
                    {
                        ByteBuffer buffer = ByteBuffer.allocate(8);
                        buffer.putLong(System.currentTimeMillis());
                        buffer.flip();

                        SendMessage(new Message(null, 0, MessageType.PONG, buffer.array()));
                    }
                    else
                        for (Command command : mCommands)
                            if (command.GetCommand() == message.GetType())
                                command.Execute(message);

                    if (mMessageCallbacks.containsKey(message.GetReplyID()))
                    {
                        mMessages.remove(message.GetReplyID());
                        SSMCallBack<Message> mcb = mMessageCallbacks.get(message.GetReplyID());
                        mMessageCallbacks.remove(message.GetReplyID());

                        mcb.CallBack(message);

                        toRemove.add(i);
                    }
                }

                for (int i : toRemove)
                    ((LinkedList<Message>) mReceivedQueue).remove(i);

                List<byte[]> remove = new ArrayList<byte[]>();

                for (byte[] id : mMessageCallbacks.keySet())
                    if (mMessageCallbacks.get(id).TimedOut())
                        remove.add(id);
                    else
                    {
                        Message message = mMessages.get(id);

                        if (!message.ShouldSend())
                            remove.add(id);
                        else if (message.ShouldSend() && message.CanSend())
                        {
                            message.Send();

                            if (message.GetPeer() == null)
                                SendMessage(message);
                            else
                                SendMessage(message, message.GetPeer());
                        }
                    }

                for (byte[] tRemove : remove)
                {
                    mMessageCallbacks.remove(tRemove);
                    mMessages.remove(tRemove);
                }
            } finally
            {
                mLock.unlock();
            }

            try
            {
                Thread.sleep(25);
            } catch (Exception e)
            {
            }
        }
    }
}