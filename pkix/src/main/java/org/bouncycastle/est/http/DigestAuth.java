package org.bouncycastle.est.http;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.MD5Digest;
import org.bouncycastle.crypto.io.DigestOutputStream;
import org.bouncycastle.util.Strings;
import org.bouncycastle.util.encoders.Hex;

/**
 * Implements DigestAuth.
 */
public class DigestAuth
    implements ESTHttpAuth
{

    private final String username;
    private final String password;

    private static SecureRandom secureRandom = new SecureRandom();

    public DigestAuth(String username, String password)
    {
        this.username = username;
        this.password = password;
    }


    public ESTHttpRequest applyAuth(final ESTHttpRequest request)
    {

        ESTHttpRequest r = request.newWithHijacker(new ESTHttpHijacker()
        {
            public ESTHttpResponse hijack(ESTHttpRequest req, Socket sock)
                throws Exception
            {
                ESTHttpResponse res = new ESTHttpResponse(req, sock);

                if (res.getStatusCode() == 401 && res.getHeader("WWW-Authenticate").startsWith("Digest"))
                {
                    res.close(); // Close off the last request.

                    Map<String, String> parts = HttpUtil.splitCSL("Digest", res.getHeader("WWW-Authenticate"));
                    String uri = req.url.toURI().getPath();
                    String method = req.method;
                    String realm = parts.get("realm");
                    String nonce = parts.get("nonce");
                    String opaque = parts.get("opaque");
                    String algorithm = parts.get("algorithm");
                    String qop = parts.get("qop");
                    Set<String> qopMods = new HashSet<String>();

                    // If an algorithm is not specified, default to MD5.
                    if (algorithm == null)
                    {
                        algorithm = "MD5";
                    }

                    algorithm = Strings.toLowerCase(algorithm);

                    if (qop != null)
                    {
                        qop = Strings.toLowerCase(qop);
                        String[] s = qop.split(",");
                        for (String j : s)
                        {
                            qopMods.add(j.trim());
                        }
                    }
                    else
                    {
                        qopMods.add("missing");
                    }

                    Digest dig = null;
                    if (algorithm.equals("md5") || algorithm.equals("md5-sess"))
                    {
                        dig = new MD5Digest();
                    }

                    byte[] ha1 = null;
                    byte[] ha2 = null;

                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    PrintWriter pw = new PrintWriter(new OutputStreamWriter(bos));
                    String crnonce = makeNonce(10); // TODO arbitrary?

                    if (algorithm.equals("md5-sess"))
                    {


                        pw.print(username);
                        pw.print(":");
                        pw.print(realm);
                        pw.print(":");
                        pw.print(password);
                        pw.flush();
                        String cs = Hex.toHexString(takeDigest(dig, bos.toByteArray()));

                        bos.reset();

                        pw.print(cs);
                        pw.print(":");
                        pw.print(nonce);
                        pw.print(":");
                        pw.print(crnonce);
                        pw.flush();

                        ha1 = takeDigest(dig, bos.toByteArray());
                    }
                    else
                    {

                        pw.print(username);
                        pw.print(":");
                        pw.print(realm);
                        pw.print(":");
                        pw.print(password);
                        pw.flush();
                        ha1 = takeDigest(dig, bos.toByteArray());
                    }

                    String hashHa1 = Hex.toHexString(ha1);
                    bos.reset();

                    if (qopMods.contains("auth-int"))
                    {
                        bos.reset();
                        pw.write(method);
                        pw.write(':');
                        pw.write(uri);
                        pw.write(':');
                        dig.reset();

                        // Digest body
                        DigestOutputStream dos = new DigestOutputStream(dig);
                        request.writer.ready(dos);
                        dos.flush();
                        byte[] b = new byte[dig.getDigestSize()];
                        dig.doFinal(b, 0);

                        pw.write(Hex.toHexString(b));
                        pw.flush();

                        ha2 = bos.toByteArray();

                    }
                    else if (qopMods.contains("auth"))
                    {
                        bos.reset();
                        pw.write(method);
                        pw.write(':');
                        pw.write(uri);
                        pw.flush();
                        ha2 = bos.toByteArray();
                    }

                    String hashHa2 = Hex.toHexString(takeDigest(dig, ha2));
                    bos.reset();
                    byte[] digestResult;
                    if (qopMods.contains("missing"))
                    {
                        pw.write(hashHa1);
                        pw.write(':');
                        pw.write(nonce);
                        pw.write(':');
                        pw.write(hashHa2);
                        pw.flush();
                        digestResult = bos.toByteArray();
                    }
                    else
                    {
                        pw.write(hashHa1);
                        pw.write(':');
                        pw.write(nonce);
                        pw.write(':');
                        pw.write("00000001");
                        pw.write(':');
                        pw.write(crnonce);
                        pw.write(':');

                        if (qopMods.contains("auth-int"))
                        {
                            pw.write("auth-int");
                        }
                        else
                        {
                            pw.write("auth");
                        }

                        pw.write(':');
                        pw.write(hashHa2);
                        digestResult = bos.toByteArray();
                    }

                    String digest = Hex.toHexString(takeDigest(dig, digestResult));

                    HashMap<String, String> hdr = new HashMap<String, String>();
                    hdr.put("username", username);
                    hdr.put("realm", realm);
                    hdr.put("nonce", nonce);
                    hdr.put("uri", uri);
                    hdr.put("response", digest);
                    if (qopMods.contains("auth-int"))
                    {
                        hdr.put("qpo", "auth-int");
                        hdr.put("nc", "00000001");
                        hdr.put("cnonce", crnonce);
                    }
                    else if (qopMods.contains("auth"))
                    {
                        hdr.put("qpo", "auth");
                        hdr.put("nc", "00000001");
                        hdr.put("cnonce", crnonce);
                    }
                    hdr.put("algorithm", algorithm);

                    if (opaque != null && opaque.length() > 0)
                    {
                        hdr.put("opaque", crnonce);
                    }

                    ESTHttpRequest answer = req.newWithHijacker(null);
                    answer.setHeader("Authorization", HttpUtil.mergeCSL("Digest", hdr));
                    res = req.getEstHttpClient().doRequest(answer);
                }
                return res;
            }
        });

        return r;
    }


    private byte[] takeDigest(Digest dig, byte[] b)
    {
        dig.reset();
        dig.update(b, 0, b.length);
        byte[] o = new byte[dig.getDigestSize()];
        dig.doFinal(o, 0);
        return o;
    }


    private static String makeNonce(int len)
    {
        byte[] b = new byte[len];
        secureRandom.nextBytes(b);
        return Hex.toHexString(b);
    }


}
