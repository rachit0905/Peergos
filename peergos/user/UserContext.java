package peergos.user;

import akka.actor.ActorSystem;
import peergos.corenode.AbstractCoreNode;
import peergos.corenode.HTTPCoreNode;
import peergos.crypto.SymmetricKey;
import peergos.crypto.User;
import peergos.crypto.UserPublicKey;
import peergos.storage.net.IP;
import peergos.user.fs.*;
import peergos.user.fs.erasure.Erasure;
import peergos.util.ArrayOps;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;
import scala.concurrent.Await;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.AbstractPartialFunction;

import static akka.dispatch.Futures.sequence;
import static org.junit.Assert.*;

import java.io.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UserContext
{
    public static final int MAX_USERNAME_SIZE = 1024;
    public static final int MAX_KEY_SIZE = UserPublicKey.RSA_KEY_SIZE;
    public static final int CLEARANCE_SIZE = 1024;

    private String username;
    private User us;
    private DHTUserAPI dht;
    private AbstractCoreNode core;
    private ActorSystem system;
    private Map<UserPublicKey, StaticDataElement> staticData = new TreeMap();


    public UserContext(String username, User user, DHTUserAPI dht, AbstractCoreNode core, ActorSystem system)
    {
        this.username = username;
        this.us = user;
        this.dht = dht;
        this.core = core;
        this.system = system;
    }

    public boolean register()
    {
        byte[] rawStatic = serializeStatic();
        byte[] signedHash = us.hashAndSignMessage(rawStatic);
        return core.addUsername(username, us.getPublicKey(), signedHash, rawStatic);
    }

    public synchronized byte[] serializeStatic()
    {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutput dout = new DataOutputStream(bout);
            dout.writeInt(staticData.size());
            for (UserPublicKey sharer : staticData.keySet())
                Serialize.serialize(staticData.get(sharer).toByteArray(), dout);
            return bout.toByteArray();
        } catch (IOException e) {throw new IllegalStateException(e.getMessage());}
    }

    public boolean isRegistered()
    {
        String name = core.getUsername(us.getPublicKey());
        return username.equals(name);
    }

    public boolean addSharingKey(PublicKey pub)
    {
        byte[] signedHash = us.hashAndSignMessage(pub.getEncoded());
        return core.allowSharingKey(username, pub.getEncoded(), signedHash);
    }

    private boolean addToStaticData(UserPublicKey pub, StaticDataElement root)
    {
        staticData.put(pub, root);
        byte[] rawStatic = serializeStatic();
        return core.updateStaticData(username, us.hashAndSignMessage(rawStatic), rawStatic);
    }

    public boolean sendFollowRequest(String friend)
    {
        // check friend is a registered user
        UserPublicKey friendKey = core.getPublicKey(friend);

        // create sharing keypair and give it write access
        KeyPair sharing = User.generateKeyPair();
        addSharingKey(sharing.getPublic());
        ByteArrayWrapper rootMapKey = new ByteArrayWrapper(ArrayOps.random(32));

        // add a note to our static data so we know who we sent the private key to
        SharedRootDir friendRoot = new SharedRootDir(friend, sharing.getPublic(), sharing.getPrivate(), rootMapKey, SymmetricKey.random());
        addToStaticData(new UserPublicKey(sharing.getPublic()), friendRoot);

        // send details to allow friend to share with us (i.e. we follow them)
        byte[] raw = friendRoot.toByteArray();

        byte[] payload = friendKey.encryptMessageFor(raw);
        return core.followRequest(friend, payload);
    }


    public List<byte[]> getFollowRequests()
    {
        byte[] raw = core.getFollowRequests(username);
        List<byte[]> requests = new ArrayList();
        DataInput din = new DataInputStream(new ByteArrayInputStream(raw));
        try {
            int number = din.readInt();
            for (int i=0; i < number; i++)
                requests.add(Serialize.deserializeByteArray(din, Integer.MAX_VALUE));
            return requests;
        } catch (IOException e)
        {
            e.printStackTrace();
            return requests;
        }
    }

    public SharedRootDir decodeFollowRequest(byte[] data)
    {
        byte[] decrypted = us.decryptMessage(data);
        try {
            SharedRootDir root = (SharedRootDir) StaticDataElement.deserialize(new DataInputStream(new ByteArrayInputStream(decrypted)));
            return root;
        } catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    private Future uploadFragment(Fragment f, String targetUser, User sharer, byte[] mapKey)
    {
        return dht.put(f.getHash(), f.getData(), targetUser, sharer.getPublicKey(), mapKey, sharer.hashAndSignMessage(ArrayOps.concat(sharer.getPublicKey(), f.getHash())));
    }

    private boolean uploadChunk(Metadata meta, Fragment[] fragments, String target, User sharer, byte[] mapKey)
    {

        // tell core node first to allow fragments
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        try {
            meta.serialize(dout);
            dout.flush();
        } catch (IOException e) {e.printStackTrace();}
        List<ByteArrayWrapper> allHashes = meta.getFragmentHashes();
        byte[] metaBlob = bout.toByteArray();
        core.addMetadataBlob(target, sharer.getPublicKey(), mapKey, metaBlob, sharer.hashAndSignMessage(metaBlob));
        core.addFragmentHashes(target, sharer.getPublicKey(), mapKey, metaBlob, meta.getFragmentHashes(), sharer.hashAndSignMessage(ArrayOps.concat(mapKey, metaBlob, ArrayOps.concat(allHashes))));

        // now upload fragments to DHT
        List<Future<Object>> futures = new ArrayList();
        for (Fragment f: fragments)
            try {
                futures.add(uploadFragment(f, target, sharer, mapKey));
            } catch (Exception e) {e.printStackTrace();}

        // wait for all fragments to upload
        Future<Iterable<Object>> futureListOfObjects = sequence(futures, system.dispatcher());
        FiniteDuration timeout = Duration.create(5*60, TimeUnit.SECONDS);
        try {
            Await.result(futureListOfObjects, timeout);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private Fragment[] downloadFragments(Metadata meta)
    {
        List<ByteArrayWrapper> hashes = meta.getFragmentHashes();
        Fragment[] res = new Fragment[hashes.size()];
        List<Future<byte[]>> futs = new ArrayList<Future<byte[]>>(res.length);
        for (int i=0; i < res.length; i++)
            futs.add(dht.get(hashes.get(i).data));
        Countdown<byte[]> first50 = new Countdown<byte[]>(50, futs, system.dispatcher());
        first50.await();
        List<Fragment> frags = new ArrayList<Fragment>();
        for (byte[] frag: first50.results)
            frags.add(new Fragment(frag));
        return frags.toArray(new Fragment[frags.size()]);
    }

    public Map<Location, DirAccess> getRoots()
    {
        Map<Location, DirAccess> res = new HashMap();
        for (UserPublicKey pub: staticData.keySet()) {
            StaticDataElement dataElement = staticData.get(pub);
            if (dataElement instanceof SharedRootDir) {
                DirAccess dir = recreateDir((SharedRootDir) dataElement);
                if (dir != null)
                    res.put(new Location(username, pub, ((SharedRootDir) dataElement).mapKey), dir);
            }
        }
        return res;
    }

    private DirAccess recreateDir(SharedRootDir raw)
    {
        ByteArrayWrapper mapKey = raw.mapKey;
        PublicKey pub = raw.pub;
        AbstractCoreNode.MetadataBlob meta = core.getMetadataBlob(username, pub.getEncoded(), mapKey.data);
        ByteArrayWrapper rawMeta = meta.metadata();
        try {
            return DirAccess.deserialize(new DataInputStream(new ByteArrayInputStream(rawMeta.data)), raw.rootDirKey);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static abstract class StaticDataElement
    {
        public final int type;

        public StaticDataElement(int type)
        {
            this.type = type;
        }

        public static StaticDataElement deserialize(DataInput din) throws IOException {
            int type = din.readInt();
            switch (type){
                case 1:
                    return SharedRootDir.deserialize(din);
                default: throw new IllegalStateException("Unknown DataElement Type: "+type);
            }
        }

        public byte[] toByteArray()
        {
            try {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                serialize(new DataOutputStream(bout));
                return bout.toByteArray();
            } catch (IOException e) {throw new IllegalStateException(e.getMessage());}
        }

        public void serialize(DataOutput dout) throws IOException
        {
            dout.writeInt(type);
        }
    }

    private static class SharedRootDir extends StaticDataElement
    {
        public final String username;
        public final PublicKey pub;
        public final PrivateKey priv;
        public final ByteArrayWrapper mapKey;
        public final SymmetricKey rootDirKey;

        public SharedRootDir(String username, PublicKey pub, PrivateKey priv, ByteArrayWrapper mapKey, SymmetricKey rootDirKey)
        {
            super(1);
            this.username = username;
            this.pub = pub;
            this.priv = priv;
            this.mapKey = mapKey;
            this.rootDirKey = rootDirKey;
        }

        public static SharedRootDir deserialize(DataInput din) throws IOException
        {
            String username = Serialize.deserializeString(din, MAX_USERNAME_SIZE);
            byte[] pubBytes = Serialize.deserializeByteArray(din, MAX_KEY_SIZE);
            byte[] privBytes = Serialize.deserializeByteArray(din, MAX_KEY_SIZE);
            ByteArrayWrapper mapKey = new ByteArrayWrapper(Serialize.deserializeByteArray(din, MAX_KEY_SIZE));
            byte[] secretRootDirKey = Serialize.deserializeByteArray(din, MAX_KEY_SIZE);
            return new SharedRootDir(username, UserPublicKey.deserializePublic(pubBytes), User.deserializePrivate(privBytes), mapKey, new SymmetricKey(secretRootDirKey));
        }

        @Override
        public void serialize(DataOutput dout) throws IOException {
            super.serialize(dout);
            // TODO encrypt this
            Serialize.serialize(username, dout);
            Serialize.serialize(pub.getEncoded(), dout);
            Serialize.serialize(priv.getEncoded(), dout);
            Serialize.serialize(mapKey.data, dout);
            Serialize.serialize(rootDirKey.getKey().getEncoded(), dout);
        }
    }

    public static class Countdown<V>
    {
        CountDownLatch left;
        List<V> results;

        public Countdown(int needed, List<Future<V>> futs, ExecutionContext context)
        {
            left = new CountDownLatch(needed);
            results = new CopyOnWriteArrayList<V>();
            for (Future<V> fut: futs)
                fut.onSuccess(new AbstractPartialFunction<V, Object>() {
                    @Override
                    public boolean isDefinedAt(V v) {
                        left.countDown();
                        results.add(v);
                        return false;
                    }
                }, context);
        }

        public void await()
        {
            while (left.getCount() > 0)
                try {
                    left.await();
                } catch (InterruptedException e) {}
        }
    }

    public static class Test
    {
        public Test() {}

        @org.junit.Test
        public void all()
        {
            try {
                ActorSystem system = null;
                String coreIP = IP.getMyPublicAddress().getHostAddress();
                String storageIP = IP.getMyPublicAddress().getHostAddress();
                int storagePort = 8000;
                try {
                    system = ActorSystem.create("UserRouter");

                    URL coreURL = new URL("http://" + coreIP + ":" + AbstractCoreNode.PORT + "/");
                    HTTPCoreNode clientCoreNode = new HTTPCoreNode(coreURL);

                    // create a new us
                    long t1 = System.nanoTime();
                    User ourKeys = User.random();
                    long t2 = System.nanoTime();
                    System.out.printf("User generation took %d mS\n", (t2-t1)/1000000);
                    String ourname = "Bob";

                    // create a DHT API
                    DHTUserAPI dht = new HttpsUserAPI(new InetSocketAddress(InetAddress.getByName(storageIP), storagePort), system);

                    // make and register us
                    UserContext us = new UserContext(ourname, ourKeys, dht, clientCoreNode, system);
                    assertTrue("Not already registered", !us.isRegistered());
                    assertTrue("Register", us.register());

                    // make another user
                    t1 = System.nanoTime();
                    User friendKeys = User.random();
                    t2 = System.nanoTime();
                    System.out.printf("User generation took %d mS\n", (t2-t1)/1000000);
                    String friendName = "Alice";
                    UserContext alice = new UserContext(friendName, friendKeys, dht, clientCoreNode, system);
                    alice.register();

                    // make Alice follow Bob (Alice gives Bob write permission to a folder in Alice's space)
                    alice.sendFollowRequest(ourname);

                    // get the sharing key alice sent us
                    List<byte[]> reqs = us.getFollowRequests();
                    assertTrue("Got follow Request", reqs.size() == 1);
                    SharedRootDir root = us.decodeFollowRequest(reqs.get(0));
                    User sharer = new User(root.priv, root.pub);

                    // store a chunk in alices space using the permitted sharing key (this could be alice or bob at this point)
                    int frags = 60;
                    for (int i = 0; i < frags; i++) {
                        byte[] signature = sharer.hashAndSignMessage(ArrayOps.concat(sharer.getPublicKey(), new byte[10 + i]));
                        clientCoreNode.registerFragmentStorage(friendName, new InetSocketAddress("localhost", 666), friendName, root.pub.getEncoded(), new byte[10 + i], signature);
                    }
                    long quota = clientCoreNode.getQuota(friendName);
                    System.out.println("Generated quota: "+quota);
                    t1 = System.nanoTime();
                    fileTest(alice.username, sharer, alice, us);
                    t2 = System.nanoTime();
                    System.out.printf("Chunk test took %d mS\n", (t2-t1)/1000000);


                } finally {
                    system.shutdown();
                }
            } catch (Throwable t)
            {
                t.printStackTrace();
            }
        }

        public void fileTest(String owner, User sharer, UserContext receiver, UserContext sender)
        {
            // create a root dir and a file to it, then retrieve and decrypt the file using the receiver
            // create root cryptree
            SymmetricKey rootRKey = SymmetricKey.random();
            SymmetricKey rootWKey = SymmetricKey.random();
            String name = "/";
            byte[] rootMapKey = ArrayOps.random(32); // root will be stored under this in the core node
            DirAccess root = new DirAccess(rootRKey, name.getBytes(), rootWKey);

            // generate file (single chunk)
            Random r = new Random();
            byte[] initVector = new byte[SymmetricKey.IV_SIZE];
            r.nextBytes(initVector);
            byte[] raw = new byte[Chunk.MAX_SIZE];
            byte[] template = "Hello secure cloud! Goodbye NSA!".getBytes();
            for (int i=0; i < raw.length/32; i++)
                System.arraycopy(template, 0, raw, 32*i, 32);

            // add file to root dir
            String filename = "tree.jpg"; // /photos/tree.jpg
            SymmetricKey fileKey = SymmetricKey.random();
            byte[] fileMapKey = ArrayOps.random(32); // file metablob will be stored under this in the core node
            Location fileLocation = new Location(owner, sharer, new ByteArrayWrapper(fileMapKey));
            FileAccess file = new FileAccess(fileKey, filename.getBytes());
            root.addFile(fileLocation, rootRKey, fileKey);

            Chunk chunk = new Chunk(raw, fileKey);
            EncryptedChunk encryptedChunk = new EncryptedChunk(chunk.encrypt(initVector));
            Fragment[] fragments = encryptedChunk.generateFragments();

            // now write the root to the core nodes
            sender.uploadChunk(root, new Fragment[0], owner, sharer, rootMapKey);
            // now upload the file meta blob
            sender.uploadChunk(file, fragments, owner, sharer, fileMapKey);


            // now check the retrieval from zero knowledge
            Map<Location, DirAccess> roots = receiver.getRoots();
            for (Location loc: roots.keySet()) {
                DirAccess dir = roots.get(loc);
                List<FileAccess> files = dir.getFiles();
            }
        }

        public void chunkTest(String destUser, User sharer, UserContext sender)
        {
            Random r = new Random();
            byte[] initVector = new byte[SymmetricKey.IV_SIZE];
            r.nextBytes(initVector);
            byte[] raw = new byte[Chunk.MAX_SIZE];
            byte[] contents = "Hello secure cloud! Goodbye NSA!".getBytes();
            for (int i=0; i < raw.length/32; i++)
                System.arraycopy(contents, 0, raw, 32*i, 32);

            SymmetricKey key = SymmetricKey.random();
            Chunk chunk = new Chunk(raw, key);
            EncryptedChunk encryptedChunk = new EncryptedChunk(chunk.encrypt(initVector));
            Fragment[] fragments = encryptedChunk.generateFragments();
            Metadata meta = new Metadata(fragments, initVector);

            // upload chunk
            long t1 = System.nanoTime();
            sender.uploadChunk(meta, fragments, destUser, sharer, new byte[10]);
            long t2 = System.nanoTime();
            System.out.printf("Chunk upload took %d mS\n", (t2-t1)/1000000);

            // retrieve chunk
            t1 = System.nanoTime();
            Fragment[] retrievedfragments = sender.downloadFragments(meta);
            t2 = System.nanoTime();
            System.out.printf("Chunk download took %d mS\n", (t2-t1)/1000000);

            t1 = System.nanoTime();
            byte[] enc = Erasure.recombine(reorder(meta, retrievedfragments), raw.length, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
            t2 = System.nanoTime();
            System.out.printf("Chunk Erasure decoding took %d mS\n", (t2-t1)/1000000);

            EncryptedChunk encrypted = new EncryptedChunk(enc);
            t1 = System.nanoTime();
            byte[] original = encrypted.decrypt(chunk.getKey(), initVector);
            t2 = System.nanoTime();
            System.out.printf("Chunk Decryption took %d mS\n", (t2-t1)/1000000);
            assertTrue("Retrieve chunk identical to original", Arrays.equals(raw, original));
        }
    }

    public static byte[][] reorder(Metadata meta, Fragment[] received)
    {
        List<ByteArrayWrapper> hashes = meta.getFragmentHashes();
        byte[][] originalHashes = new byte[hashes.size()][];
        for (int i=0; i < originalHashes.length; i++)
            originalHashes[i] = hashes.get(i).data;
        byte[][] res = new byte[originalHashes.length][];
        for (int i=0; i < res.length; i++)
        {
            for (int j=0; j < received.length; j++)
                if (Arrays.equals(originalHashes[i], received[j].getHash()))
                {
                    res[i] = received[j].getData();
                    break;
                }
            if (res[i] == null)
                res[i] = new byte[received[0].getData().length];
        }
        return res;
    }

    public static byte[] randomClearanceData()
    {
        Random r = new Random();
        byte[] clearanceData = new byte[CLEARANCE_SIZE];
        r.nextBytes(clearanceData);
        return clearanceData;
    }
}