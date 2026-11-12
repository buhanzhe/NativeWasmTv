"""Real local TLS: extra trust anchors must not accept invalid certs/hostnames."""
import os,pathlib,subprocess,tempfile,datetime,http.server,threading,functools
from cryptography import x509
from cryptography.x509.oid import NameOID,AuthorityInformationAccessOID
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
root=pathlib.Path(__file__).resolve().parents[1]
libs=root/'.codex-tmp/all-sites-20261109/windows-core/lib'
if not libs.is_dir():
 libs=root/'browser-core/litebrowser/windows/build/lib'
jars=list(libs.glob('*.jar'));assert jars,'Build desktop core dependencies first'
code=r'''package xiao.bu.tv;
import java.io.*;import java.security.*;import java.security.cert.*;import javax.net.ssl.*;import okhttp3.*;
class Check {
 static KeyStore store(String path)throws Exception {KeyStore ks=KeyStore.getInstance("PKCS12");try(InputStream in=new FileInputStream(path)){ks.load(in,"changeit".toCharArray());}return ks;}
 static X509TrustManager trust(KeyStore ks)throws Exception {TrustManagerFactory f=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());f.init(ks);return (X509TrustManager)f.getTrustManagers()[0];}
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static void rejects(X509TrustManager manager,X509Certificate cert)throws Exception {boolean rejected=false;try{manager.checkServerTrusted(new X509Certificate[]{cert},"RSA");}catch(CertificateException expected){rejected=true;}ok(rejected);}
 public static void main(String[] args)throws Exception {
  KeyStore valid=store(args[0]),expired=store(args[1]),unknown=store(args[2]);
  X509TrustManager manager=DesktopTrust.combine(trust(null),trust(valid));
  manager.checkServerTrusted(new X509Certificate[]{(X509Certificate)valid.getCertificate("test")},"RSA");
  rejects(manager,(X509Certificate)unknown.getCertificate("test"));
  // Even a directly trusted expired certificate must fail.
  rejects(DesktopTrust.combine(trust(expired)),(X509Certificate)expired.getCertificate("test"));
  CertificateFactory cf=CertificateFactory.getInstance("X.509");
  X509Certificate anchor,leaf;
  try(InputStream in=new FileInputStream(args[3])){anchor=(X509Certificate)cf.generateCertificate(in);}
  try(InputStream in=new FileInputStream(args[4])){leaf=(X509Certificate)cf.generateCertificate(in);}
  KeyStore roots=KeyStore.getInstance("JKS");roots.load(null,null);roots.setCertificateEntry("root",anchor);
  DesktopTrust.combine(trust(roots)).checkServerTrusted(new X509Certificate[]{leaf},"RSA");
  // AIA retrieval supplies intermediates, never new trust anchors.
  rejects(DesktopTrust.combine(trust(valid)),leaf);
  KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(valid,"changeit".toCharArray());
  SSLContext server=SSLContext.getInstance("TLS");server.init(keys.getKeyManagers(),null,null);
  try(SSLServerSocket listener=(SSLServerSocket)server.getServerSocketFactory().createServerSocket(0,4,java.net.InetAddress.getLoopbackAddress())){
   Thread thread=new Thread(()->{while(!listener.isClosed())try(SSLSocket socket=(SSLSocket)listener.accept()){
    socket.setSoTimeout(3000);BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream()));String line;while((line=in.readLine())!=null&&!line.isEmpty()){}
    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".getBytes());
   }catch(Exception ignored){}});thread.setDaemon(true);thread.start();
   SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{manager},null);
   OkHttpClient client=new OkHttpClient.Builder().sslSocketFactory(tls.getSocketFactory(),manager).build();
   try{
    try(Response response=client.newCall(new Request.Builder().url("https://localhost:"+listener.getLocalPort()).build()).execute()){ok(response.isSuccessful()&&response.body().string().equals("OK"));}
    boolean rejected=false;try(Response response=client.newCall(new Request.Builder().url("https://127.0.0.1:"+listener.getLocalPort()).build()).execute()){}catch(SSLPeerUnverifiedException expected){rejected=true;}ok(rejected);
   }finally{client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
  }
  System.out.println("PASS fallback trust, untrusted/expired certificates and real TLS hostname validation");
 }
}'''
with tempfile.TemporaryDirectory() as folder:
 p=pathlib.Path(folder)
 server=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(http.server.SimpleHTTPRequestHandler,directory=folder))
 threading.Thread(target=server.serve_forever,daemon=True).start()
 now=datetime.datetime.now(datetime.timezone.utc)
 def certificate(name,key,issuer,issuer_key,ca,aia=None):
  subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,name)])
  builder=x509.CertificateBuilder().subject_name(subject).issuer_name(issuer).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(days=1)).not_valid_after(now+datetime.timedelta(days=2)).add_extension(x509.BasicConstraints(ca=ca,path_length=None),critical=True)
  if aia:builder=builder.add_extension(x509.AuthorityInformationAccess([x509.AccessDescription(AuthorityInformationAccessOID.CA_ISSUERS,x509.UniformResourceIdentifier(aia))]),critical=False)
  return builder.sign(issuer_key,hashes.SHA256())
 root_key=rsa.generate_private_key(public_exponent=65537,key_size=2048);root_name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'AIA test root')]);root_cert=certificate('AIA test root',root_key,root_name,root_key,True)
 issuer_key=rsa.generate_private_key(public_exponent=65537,key_size=2048);issuer_cert=certificate('AIA issuer',issuer_key,root_cert.subject,root_key,True)
 leaf_key=rsa.generate_private_key(public_exponent=65537,key_size=2048);leaf_cert=certificate('AIA leaf',leaf_key,issuer_cert.subject,issuer_key,False,'http://127.0.0.1:'+str(server.server_port)+'/issuer.cer')
 for name,cert in [('root',root_cert),('issuer',issuer_cert),('leaf',leaf_cert)]: (p/(name+'.cer')).write_bytes(cert.public_bytes(serialization.Encoding.DER))
 for name,extra in [('valid',[]),('expired',['-startdate','-365d','-validity','1']),('unknown',[])]:
  command=['keytool','-genkeypair','-alias','test','-keyalg','RSA','-keysize','2048','-dname','CN=localhost','-ext','SAN=dns:localhost','-storetype','PKCS12','-keystore',str(p/(name+'.p12')),'-storepass','changeit','-keypass','changeit','-noprompt']
  subprocess.run(command+extra,check=True,capture_output=True)
 check=p/'Check.java';check.write_text(code,'utf8');cp=os.pathsep.join(map(str,jars))
 subprocess.run(['javac','-cp',cp,'-d',folder,*[str(root/'browser-core/litebrowser/desktop/java/xiao/bu/tv'/name) for name in ['DesktopTrust.java','DesktopIssuers.java']],str(check)],check=True)
 try:subprocess.run(['java','-cp',folder+os.pathsep+cp,'xiao.bu.tv.Check',*[str(p/(n+'.p12')) for n in ['valid','expired','unknown']],str(p/'root.cer'),str(p/'leaf.cer')],check=True)
 finally:server.shutdown();server.server_close()
