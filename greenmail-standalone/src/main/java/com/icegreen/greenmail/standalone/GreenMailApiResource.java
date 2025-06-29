package com.icegreen.greenmail.standalone;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.user.UserException;
import com.icegreen.greenmail.user.UserManager;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Scanner;

/**
 * Exposes GreenMail API and openapi.
 */
@Path("/")
public class GreenMailApiResource {
    private static final Logger LOG = LoggerFactory.getLogger(GreenMailApiResource.class);
    private final GreenMail greenMail;
    private final ServerSetup[] serverSetups;
    private final GreenMailConfiguration configuration;

    public GreenMailApiResource(GreenMail greenMail, ServerSetup[] serverSetups, GreenMailConfiguration configuration) {
        this.greenMail = greenMail;
        this.serverSetups = serverSetups;
        this.configuration = configuration;
    }

    // UI
    private static final String INDEX_CONTENT = loadResource("index.html");
    private static final String OPENAPI_CONTENT = loadResource("greenmail-openapi.yml");
    private static final String JS_RAPIDOC = loadResource("js/rapidoc-min.js");

    private static String loadResource(String name) {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            if(null == is) {
                throw new IllegalArgumentException("Can not load resource " + name + " from classpath");
            }
            return new Scanner(is, StandardCharsets.UTF_8.name()).useDelimiter("\\A").next();
        } catch (IOException | NullPointerException e) {
            throw new IllegalArgumentException("Can not load resource " + name + " from classpath", e);
        }
    }

    @GET
    @Produces("text/html")
    public String index() {
        return INDEX_CONTENT;
    }

    @Path("/greenmail-openapi.yml")
    @GET
    @Produces("application/yaml")
    public String openapi() {
        return OPENAPI_CONTENT;
    }

    @Path("/js/rapidoc-min.js")
    @GET
    @Produces("text/javascript")
    public String jsRapidoc() {
        return JS_RAPIDOC;
    }

    // General
    abstract static class AbstractMessage {
        private final String message;

        protected AbstractMessage(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    static class SuccessMessage extends AbstractMessage {
        protected SuccessMessage(String message) {
            super(message);
        }
    }

    static class ErrorMessage extends AbstractMessage {
        protected ErrorMessage(String message) {
            super(message);
        }
    }

    // Configuration
    static class Configuration {
        public ServerSetup[] serverSetups;
        public boolean authenticationDisabled;
        public boolean sieveIgnoreDetail;
        public String preloadDirectory;
    }

    @GET
    @Path("/api/configuration")
    @Produces("application/json")
    public Response configuration() {
        final Configuration config = new Configuration();
        config.serverSetups = serverSetups;
        config.authenticationDisabled = configuration.isAuthenticationDisabled();
        config.sieveIgnoreDetail = configuration.isSieveIgnoreDetailEnabled();
        config.preloadDirectory = configuration.getPreloadDir();
        return Response.status(Response.Status.OK)
            .entity(config)
            .build();
    }

    // User
    public static class User {
        public String email;
        public String login;
        public String password;
    }

    /**
     * Custom mapped, see {@link JacksonObjectMapperProvider.GreenMailUserSerializer}
     */
    @GET
    @Path("/api/user")
    @Produces("application/json")
    public Collection<GreenMailUser> listUsers() {
        return greenMail.getUserManager().listUser();
    }

    @POST
    @Path("/api/user")
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces("application/json")
    public Response createUsers(User newUser) {
        try {
            final GreenMailUser user = greenMail.getUserManager().createUser(newUser.email, newUser.login, newUser.password);
            LOG.debug("Created user {}", user);
            return Response.status(Response.Status.OK)
                .entity(user)
                .build();
        } catch (UserException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorMessage("Can not create user : " + e.getMessage()))
                .build();
        }
    }

    @DELETE
    @Path("/api/user/{emailOrLogin}")
    @Produces("application/json")
    public Response deleteUserById(@PathParam("emailOrLogin") String emailOrLogin) {
        final UserManager userManager = greenMail.getUserManager();
        final GreenMailUser user = getUserByLoginOrEmail(emailOrLogin, userManager);
        if (null == user) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorMessage("User '" + emailOrLogin + "' not found")).build();
        }
        LOG.debug("Deleting user {}", user);
        userManager.deleteUser(user);
        return Response.status(Response.Status.OK)
            .entity(new SuccessMessage("User '" + emailOrLogin + "' deleted")).build();
    }

    /**
     * Gets emails for existing user and INBOX folder.
     *
     * @param emailOrLogin the user email or login.
     *                     Custom mapped, see {@link JacksonObjectMapperProvider.StoredMessageSerializer}
     */
    @GET
    @Path("/api/user/{emailOrLogin}/messages/")
    @Produces("application/json")
    public Response listMessages(@PathParam("emailOrLogin") String emailOrLogin) {
        return listMessages(emailOrLogin, null);
    }

    /**
     * Gets emails for existing user and given folder.
     *
     * @param emailOrLogin the user email or login.
     * @param folderName   the name of the folder. Defaults to INBOX.
     *                     Custom mapped, see {@link JacksonObjectMapperProvider.StoredMessageSerializer}
     */
    @GET
    @Path("/api/user/{emailOrLogin}/messages/{folderName}/")
    @Produces("application/json")
    public Response listMessages(@PathParam("emailOrLogin") String emailOrLogin, @PathParam("folderName") String folderName) {
        final UserManager userManager = greenMail.getUserManager();
        final GreenMailUser user = getUserByLoginOrEmail(emailOrLogin, userManager);
        if (null == user) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorMessage("User '" + emailOrLogin + "' not found")).build();
        }
        MailFolder folder = getFolderOrDefault(user, folderName);
        if (null == folder) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorMessage("User '" + emailOrLogin + "' does not have mailbox folder '" + folderName + "'")).build();
        }

        return Response.status(Response.Status.OK)
            .entity(folder.getMessages()).build();
    }

    private static GreenMailUser getUserByLoginOrEmail(String emailOrLogin, UserManager userManager) {
        LOG.debug("Searching user using '{}'", emailOrLogin);
        GreenMailUser user = userManager.getUser(emailOrLogin);
        if (null == user) {
            user = userManager.getUserByEmail(emailOrLogin);
        }
        return user;
    }

    private MailFolder getFolderOrDefault(GreenMailUser user, String folderName) {
        LOG.debug("Getting for user '{}' folder '{}'", user.getEmail(), folderName);
        if (null == folderName || folderName.isEmpty()) {
            try {
                return greenMail.getManagers().getImapHostManager().getInbox(user);
            } catch (FolderException e) {
                return null;
            }
        } else {
            return greenMail.getManagers().getImapHostManager().getFolder(user, folderName);
        }
    }

    // Operations
    @POST
    @Path("/api/mail/purge")
    @Produces("application/json")
    public AbstractMessage purge() {
        try {
            greenMail.purgeEmailFromAllMailboxes();
            return new SuccessMessage("Purged mails");
        } catch (FolderException e) {
            return new ErrorMessage("Can not purge mails : " + e.getMessage());
        }
    }

    @POST
    @Path("/api/service/reset")
    @Produces("application/json")
    public AbstractMessage reset() {
        greenMail.reset();
        return new SuccessMessage("Performed reset");
    }


    @GET
    @Path("/api/service/readiness")
    @Produces("application/json")
    public Response ready() {
        if (greenMail.isRunning()) {
            return Response.status(Response.Status.OK)
                .entity(new SuccessMessage("Service running")).build();
        } else {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(new SuccessMessage("Service not running")).build();
        }
    }
}
