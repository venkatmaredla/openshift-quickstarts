package org.openshift.quickstarts.procexecserver.library.client;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.jms.ConnectionFactory;
import javax.jms.Queue;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.kie.api.KieServices;
import org.kie.api.command.BatchExecutionCommand;
import org.kie.api.command.Command;
import org.kie.api.command.KieCommands;
import org.kie.api.runtime.ExecutionResults;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.QueryResults;
import org.kie.api.runtime.rule.QueryResultsRow;
import org.kie.remote.common.rest.KieRemoteHttpRequest;
import org.kie.server.api.marshalling.Marshaller;
import org.kie.server.api.marshalling.MarshallerFactory;
import org.kie.server.api.marshalling.MarshallingFormat;
import org.kie.server.api.model.ServiceResponse;
import org.kie.server.client.KieServicesConfiguration;
import org.kie.server.client.KieServicesFactory;
import org.kie.server.client.ProcessServicesClient;
import org.kie.server.client.RuleServicesClient;
import org.openshift.quickstarts.procexecserver.library.types.Book;
import org.openshift.quickstarts.procexecserver.library.types.Loan;
import org.openshift.quickstarts.procexecserver.library.types.LoanRequest;
import org.openshift.quickstarts.procexecserver.library.types.LoanResponse;
import org.openshift.quickstarts.procexecserver.library.types.ReturnRequest;
import org.openshift.quickstarts.procexecserver.library.types.ReturnResponse;
import org.openshift.quickstarts.procexecserver.library.types.Suggestion;
import org.openshift.quickstarts.procexecserver.library.types.SuggestionRequest;
import org.openshift.quickstarts.procexecserver.library.types.SuggestionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LibraryClient {

    private static final Logger logger = LoggerFactory.getLogger(LibraryClient.class);

    public static void main(String... args) throws Exception {
        LibraryClient client = new LibraryClient();
        String command = (args != null && args.length > 0) ? args[0] : null;
        LibraryCallback callback = new LibraryCallback();
        if (client.runCommand(command, callback)) {
            logger.info("********** " + callback.getSuggestion().getBook().getTitle() + " **********");
        } else {
            throw new Exception("Nothing run! Must specify -Dexec.args=runLocal (or runRemoteRest, runRemoteHornetMQ, runRemoteActiveMQ).");
        }
    }

    // package-protected for LibraryServlet
    boolean runCommand(String command, LibraryCallback callback) throws Exception {
        boolean run = false;
        command = trimToNull(command);
        LibraryClient client = new LibraryClient();
        if ("runLocal".equals(command)) {
            client.runLocal(callback);
            run = true;
        } else if ("runRemoteRest".equals(command)) {
            client.runRemoteRest(callback);
            run = true;
        } else if ("runRemoteHornetQ".equals(command)) {
            client.runRemoteHornetQ(callback);
            run = true;
        } else if ("runRemoteActiveMQ".equals(command)) {
            client.runRemoteActiveMQ(callback);
            run = true;
        }
        return run;
    }

    // package-protected for LibraryTest
    void runLocal(LibraryCallback callback) {
        KieContainer container = KieServices.Factory.get().getKieClasspathContainer();
        KieSession session = container.newKieSession();
        BatchExecutionCommand batch = createSuggestionRequestBatch();
        ExecutionResults execResults = session.execute(batch);
        handleSugestionRequestResults(callback, execResults);
    }

    private void runRemoteRest(LibraryCallback callback) throws Exception {
        String baseurl = getBaseUrl(callback, "http", "localhost", "8080");
        String resturl = baseurl + "/kie-server/services/rest/server";
        logger.debug("---------> resturl: " + resturl);
        String username = getUsername(callback);
        String password = getPassword(callback);
        KieServicesConfiguration config = KieServicesFactory.newRestConfiguration(resturl, username, password);
        if (resturl.toLowerCase().startsWith("https")) {
            config.setUseSsl(true);
            forgiveUnknownCert();
        }
        runRemote(callback, config);
    }

    private void runRemoteHornetQ(LibraryCallback callback) throws Exception {
        String baseurl = getBaseUrl(callback, "remote", "localhost", "4447");
        String username = getUsername(callback);
        String password = getPassword(callback);
        String qusername = getQUsername(callback);
        String qpassword = getQPassword(callback);
        Properties props = new Properties();
        props.setProperty(Context.INITIAL_CONTEXT_FACTORY, "org.jboss.naming.remote.client.InitialContextFactory");
        props.setProperty(Context.PROVIDER_URL, baseurl);
        props.setProperty(Context.SECURITY_PRINCIPAL, username);
        props.setProperty(Context.SECURITY_CREDENTIALS, password);
        InitialContext context = new InitialContext(props);
        KieServicesConfiguration config = KieServicesFactory.newJMSConfiguration(context, qusername, qpassword);
        runRemote(callback, config);
    }

    private void runRemoteActiveMQ(LibraryCallback callback) throws Exception {
        String baseurl = getBaseUrl(callback, "tcp", "localhost", "61616");
        String username = getUsername(callback);
        String password = getPassword(callback);
        String qusername = getQUsername(callback);
        String qpassword = getQPassword(callback);
        Properties props = new Properties();
        props.setProperty(Context.INITIAL_CONTEXT_FACTORY, "org.apache.activemq.jndi.ActiveMQInitialContextFactory");
        props.setProperty(Context.PROVIDER_URL, baseurl);
        props.setProperty(Context.SECURITY_PRINCIPAL, username);
        props.setProperty(Context.SECURITY_CREDENTIALS, password);
        InitialContext context = new InitialContext(props);
        ConnectionFactory connectionFactory = (ConnectionFactory)context.lookup("ConnectionFactory");
        Queue requestQueue = (Queue)context.lookup("dynamicQueues/queue/KIE.SERVER.REQUEST");
        Queue responseQueue = (Queue)context.lookup("dynamicQueues/queue/KIE.SERVER.RESPONSE");
        KieServicesConfiguration config = KieServicesFactory.newJMSConfiguration(connectionFactory, requestQueue, responseQueue, qusername, qpassword);
        runRemote(callback, config);
    }

    private void runRemote(LibraryCallback callback, KieServicesConfiguration config) {
        config.setMarshallingFormat(MarshallingFormat.XSTREAM);
        RuleServicesClient client = KieServicesFactory.newKieServicesClient(config).getServicesClient(RuleServicesClient.class);
        BatchExecutionCommand batch = createSuggestionRequestBatch();
        ServiceResponse<String> response = client.executeCommands("LibraryContainer", batch);
        logger.info(String.valueOf(response));
        Set<Class<?>> classes = new HashSet<Class<?>>();
        classes.add(Book.class);
        classes.add(Loan.class);
        classes.add(LoanRequest.class);
        classes.add(LoanResponse.class);
        classes.add(ReturnRequest.class);
        classes.add(ReturnResponse.class);
        classes.add(Suggestion.class);
        classes.add(SuggestionRequest.class);
        classes.add(SuggestionResponse.class);
        Marshaller marshaller = MarshallerFactory.getMarshaller(classes, config.getMarshallingFormat(), Book.class.getClassLoader());
        ExecutionResults execResults = marshaller.unmarshall(response.getResult(), ExecutionResults.class);
        handleSugestionRequestResults(callback, execResults);
    }

    @SuppressWarnings("unused")
    private ProcessServicesClient getProcessServicesClient(KieServicesConfiguration config) {
        // see org.kie.server.client.helper.JBPMServicesClientBuilder
        ProcessServicesClient client = KieServicesFactory.newKieServicesClient(config).getServicesClient(ProcessServicesClient.class);
        return client;
    }

    private BatchExecutionCommand createSuggestionRequestBatch() {
        SuggestionRequest request = new SuggestionRequest();
        request.setKeyword("Zombie");
        List<Command<?>> cmds = new ArrayList<Command<?>>();
        KieCommands commands = KieServices.Factory.get().getCommands();
        cmds.add(commands.newInsert(request));
        cmds.add(commands.newFireAllRules());
        cmds.add(commands.newQuery("suggestions", "get suggestion"));
        return commands.newBatchExecution(cmds, "LibrarySession");
    }

    private void handleSugestionRequestResults(LibraryCallback callback, ExecutionResults execResults) {
        QueryResults queryResults = (QueryResults)execResults.getValue("suggestions");
        if (queryResults != null) {
            callback.setQueryResultsSize(queryResults.size());
            for (QueryResultsRow queryResult : queryResults) {
                SuggestionResponse suggestionResponse = (SuggestionResponse)queryResult.get("suggestion");
                if (suggestionResponse != null) {
                    callback.setSuggestion(suggestionResponse.getSuggestion());
                    break;
                }
            }
        }
    }

    private String getBaseUrl(LibraryCallback callback, String defaultProtocol, String defaultHost, String defaultPort) {
        String protocol = trimToNull(callback.getProtocol());
        if (protocol == null) {
            protocol = trimToNull(System.getProperty("protocol", defaultProtocol));
        }
        String host = trimToNull(callback.getHost());
        if (host == null) {
            host = trimToNull(System.getProperty("host", System.getProperty("jboss.bind.address", defaultHost)));
        }
        String port = trimToNull(callback.getPort());
        if (port == null) {
            if ("https".equalsIgnoreCase(protocol)) {
                defaultPort = null;
            }
            port = trimToNull(System.getProperty("port", defaultPort));
        }
        String baseurl = protocol + "://" + host + (port != null ? ":" + port : "");
        logger.info("---------> baseurl: " + baseurl);
        return baseurl;
    }

    private String getUsername(LibraryCallback callback) {
        String username = trimToNull(callback.getUsername());
        if (username == null) {
            username = trimToNull(System.getProperty("username", "kieserver"));
        }
        logger.debug("---------> username: " + username);
        return username;
    }

    private String getPassword(LibraryCallback callback) {
        String password = callback.getPassword();
        if (password == null) {
            password = System.getProperty("password", "kieserver1!");
        }
        logger.debug("---------> password: " + password);
        return password;
    }

    private String getQUsername(LibraryCallback callback) {
        String qusername = trimToNull(callback.getQUsername());
        if (qusername == null) {
            qusername = trimToNull(System.getProperty("qusername", getUsername(callback)));
        }
        logger.debug("---------> qusername: " + qusername);
        return qusername;
    }

    private String getQPassword(LibraryCallback callback) {
        String qpassword = callback.getQPassword();
        if (qpassword == null) {
            qpassword = System.getProperty("qpassword", getPassword(callback));
        }
        logger.debug("---------> qpassword: " + qpassword);
        return qpassword;
    }

    private String trimToNull(String str) {
        if (str != null) {
            str = str.trim();
            if (str.length() == 0) {
                str = null;
            }
        }
        return str;
    }

    // only needed for non-production test scenarios where the TLS certificate isn't set up properly
    private void forgiveUnknownCert() throws Exception {
        KieRemoteHttpRequest.ConnectionFactory connf = new KieRemoteHttpRequest.ConnectionFactory() {
            public HttpURLConnection create(URL u) throws IOException {
                return forgiveUnknownCert((HttpURLConnection)u.openConnection());
            }
            public HttpURLConnection create(URL u, Proxy p) throws IOException {
                return forgiveUnknownCert((HttpURLConnection)u.openConnection(p));
            }
            private HttpURLConnection forgiveUnknownCert(HttpURLConnection conn) throws IOException {
                if (conn instanceof HttpsURLConnection) {
                    HttpsURLConnection sconn = HttpsURLConnection.class.cast(conn);
                    sconn.setHostnameVerifier(new HostnameVerifier() {
                        public boolean verify(String arg0, SSLSession arg1) {
                            return true;
                        }
                    });
                    try {
                        SSLContext context = SSLContext.getInstance("TLS");
                        context.init(null, new TrustManager[] {
                            new X509TrustManager() {
                                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
                                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
                                public X509Certificate[] getAcceptedIssuers() {
                                    return null;
                                }
                            }
                        }, null);
                        sconn.setSSLSocketFactory(context.getSocketFactory());
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                }
                return conn;
            }
        };
        Field field = KieRemoteHttpRequest.class.getDeclaredField("CONNECTION_FACTORY");
        field.setAccessible(true);
        field.set(null, connf);
    }

}