package services;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;

import domain.*;

/**
 * The implementation of most of the QualOpt web service features
 * 
 * @author Kaimin Li
 * 
 */
@Path("/user")
public class UserServices {

	public static ArrayList<LoginSession> sessions = new ArrayList<LoginSession>();
	public static String currentUserEmail;
	private static Study currentStudy;

	private static Properties mailServerProperties;
	private static Session getMailSession;
	private static MimeMessage generateMailMessage;

	/**
	 * Called by the client when a new researcher account needs to be made.
	 * 
	 * @param email
	 *            The user's email, used to sign in.
	 * @param password
	 * @param profession
	 * @param institute
	 * @param mailServer
	 *            This is the client's mail server. This web service will
	 *            attempt to connect to this server for mailing.
	 * @param servletResponse
	 * @return
	 * @throws Exception
	 */
	@POST
	@Path("/newuser")
	@Produces(MediaType.APPLICATION_XML)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response createUsers(@FormParam("email") String email, @FormParam("password") String password,
			@FormParam("profession") String profession, @FormParam("institute") String institute,
			@FormParam("mailserver") String mailServer, @Context HttpServletResponse servletResponse) throws Exception {

		User user = new User(email, password, profession, institute, mailServer);
		try {
			// Establish database connection to store user info into the
			// database
			DatabaseConnection database = new DatabaseConnection();
			Connection con = database.getConnection(true);
			String sql = "INSERT INTO USER_PROFILES (email, password, profession, institute, mailserver) "
					+ "values (?, ?, ?, ?, ?)";
			PreparedStatement st = con.prepareStatement(sql);

			// Sets the values of the sql string above
			st.setString(1, email);
			st.setString(2, password);
			st.setString(3, profession);
			st.setString(4, institute);
			st.setString(5, mailServer);
			st.execute();
			con.close();
		} catch (Exception e) {
			throw e;
		}
		return Response.created(URI.create("/Userservices/users/" + user.getEmail())).build();
	}

	@POST
	@Path("/login")
	@Produces("application/json")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response login(@FormParam("email") String email, @FormParam("password") String password,
			@Context HttpServletResponse servletResponse) throws Exception {
		try {
			// Retrieve a list of registered users
			DatabaseConnection database = new DatabaseConnection();
			Connection con = database.getConnection(true);
			String sql = "SELECT * FROM USER_PROFILES";
			PreparedStatement st = con.prepareStatement(sql);
			ResultSet rs = st.executeQuery();

			Logger lgr = Logger.getLogger(DatabaseConnection.class.getName());
			lgr.log(Level.INFO, "email is: " + email + " pass is: " + password);

			// Compare with what the user has sent
			while (rs.next()) {
				lgr.log(Level.INFO,
						" db email is: " + rs.getString("EMAIL") + " db pass is: " + rs.getString("PASSWORD"));

				if (rs.getString("EMAIL").equals(email) && rs.getString("PASSWORD").equals(password)) {
					lgr.log(Level.INFO, "Found a match!");

					// Generate a token for the user.
					SessionIdentifierGenerator tokenGen = new SessionIdentifierGenerator();
					String token = tokenGen.nextSessionId();
					String tokenJSON = tokenGen.tokenToJSON(token);

					// Add the user to the list of sessions
					sessions.add(new LoginSession(token, email));

					System.out.println("There are this many sessions: " + sessions.size());
					return Response.status(Status.ACCEPTED).entity(tokenJSON).build();
				}
			}
		} catch (Exception e) {
			throw e;
		}
		return Response.status(Status.FORBIDDEN).build();
	}

	@Secured
	@POST
	@Path("/newstudy")
	@Produces(MediaType.APPLICATION_XML)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response newStudy(@FormParam("name") String name, @FormParam("description") String description,
			@FormParam("incentive") String incentive, @FormParam("hasPay") String hasPay) throws Exception {

		// Set the current study to the new study
		currentStudy = new Study();
		currentStudy.setOwnerEmail(currentUserEmail);
		currentStudy.setDescription(description);
		currentStudy.setIncentive(incentive);
		currentStudy.setName(name);

		int sqlHasPay;

		if (hasPay != null) {
			currentStudy.setPaid(true);
			sqlHasPay = 1;
		} else {
			currentStudy.setPaid(false);
			sqlHasPay = 0;
		}

		// Insert the study into the database
		try {
			DatabaseConnection database = new DatabaseConnection();
			Connection con = database.getConnection(true);
			String sql = "INSERT INTO STUDY SET U_ID =(" + "SELECT ID FROM USER_PROFILES WHERE EMAIL = '"
					+ currentStudy.getOwnerEmail() + "')," + " NAME = '" + name + "', " + " DESCRIPTION = '"
					+ description + "'," + " INCENTIVE = '" + incentive + "'," + " HASPAY = '" + sqlHasPay + "';";
			PreparedStatement st = con.prepareStatement(sql);
			st.execute();

		} catch (Exception e) {
			throw e;
		}

		return Response.status(Status.ACCEPTED).build();
	}

	/**
	 * This sets the current study when the user clicks a study (called by the
	 * client)
	 * 
	 * @param studyJSON
	 */
	@Secured
	@POST
	@Path("/currentstudy")
	@Consumes({ "text/plain,text/html,application/json" })
	public void setCurrentStudy(String studyJSON) {

		// The study info will be passed as a JSON object therefore parsing is
		// required to extract the object.
		JsonParser parser = new JsonParser();
		JsonObject studyJ = parser.parse(studyJSON).getAsJsonObject();

		currentStudy = new Study();
		currentStudy.setName(studyJ.get("name").getAsString());
		currentStudy.setDescription(studyJ.get("description").getAsString());
		currentStudy.setIncentive(studyJ.get("incentive").getAsString());
		currentStudy.setPaid(studyJ.get("haspay").getAsBoolean());

	}

	@Secured
	@POST
	@Path("/updatestudy")
	@Produces(MediaType.APPLICATION_XML)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response updateStudy(@FormParam("name") String name, @FormParam("description") String description,
			@FormParam("incentive") String incentive, @FormParam("hasPay") String hasPay) throws Exception {
		
		int sqlHasPay;

		if (hasPay != null) {
			sqlHasPay = 1;
		} else {
			sqlHasPay = 0;
		}
		
		// update the study entry in the database
		try {
			DatabaseConnection database = new DatabaseConnection();
			Connection con = database.getConnection(true);
			String sql = "UPDATE STUDY SET NAME = '"+ name + 
					"',DESCRIPTION = '"+ description +
					"',INCENTIVE = '"+ incentive +
					"',HASPAY = '"+ sqlHasPay + "'  WHERE "
					+ "NAME = '" + currentStudy.getName() + "'";
			PreparedStatement st = con.prepareStatement(sql);
			st.execute();

		} catch (Exception e) {
			throw e;
		}

		return Response.status(Status.ACCEPTED).build();
	}

	/**
	 * Called when the user completes the login.
	 * 
	 * @return A list of the studies owned by the current user.
	 * @throws Exception
	 */
	@Secured
	@GET
	@Path("/allstudies")
	@Produces("application/json")
	public String getAllStudies() throws Exception {
		List<Study> allStudies = new ArrayList<Study>();
		try {
			// Fetch studies from the database
			DatabaseConnection database = new DatabaseConnection();
			Connection con = database.getConnection(true);
			String sql = "SELECT * FROM STUDY" + " WHERE U_ID=(SELECT ID FROM USER_PROFILES WHERE EMAIL='"
					+ currentUserEmail + "')";
			PreparedStatement st = con.prepareStatement(sql);
			ResultSet rs = st.executeQuery();

			while (rs.next()) {
				Study study = new Study();
				study.setDescription(rs.getString("DESCRIPTION"));
				study.setName(rs.getString("NAME"));
				study.setIncentive(rs.getString("INCENTIVE"));
				study.setOwnerEmail(currentUserEmail);
				study.setPaid(rs.getBoolean("HASPAY"));

				allStudies.add(study);
			}

		} catch (Exception e) {
			throw e;
		}
		return allStudies.toString();
	}

	/**
	 * Called by the client for emailing.
	 * 
	 * @param sender
	 *            Email address of teh sender account.
	 * @param password
	 *            Password for the sender account. This must work with the
	 *            provided mail server.
	 * @param surveyLink
	 *            Link to the survey must be provided by the user
	 * @param subject
	 * @param email
	 * @return
	 * @throws Exception
	 */
	@Secured
	@POST
	@Path("/email")
	@Produces(MediaType.APPLICATION_XML)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response emailParticipants(@FormParam("senderemail") String sender, @FormParam("password") String password,
			@FormParam("surveylink") String surveyLink, @FormParam("subject") String subject,
			@FormParam("emailbody") String email) throws Exception {
		// TODO: Filter out emails that do not wish to participate

		// Use SMTP with authentication
		mailServerProperties = System.getProperties();
		mailServerProperties.put("mail.smtp.port", "587");
		mailServerProperties.put("mail.smtp.auth", "true");
		mailServerProperties.put("mail.smtp.starttls.enable", "true");

		// Set up the message
		getMailSession = Session.getDefaultInstance(mailServerProperties, null);
		generateMailMessage = new MimeMessage(getMailSession);
		generateMailMessage.setFrom(new InternetAddress(sender));

		// Using my email to test
		generateMailMessage.addRecipient(Message.RecipientType.TO, new InternetAddress("experimental1499@gmail.com"));
		generateMailMessage.setSubject(subject);

		// Adding a redirect to the survey link to collect data
		surveyLink = new StringBuilder().append("http://localhost:8080/QualOptServer/services/user/record?link=")
				.append(surveyLink).append("&name=").append(currentStudy.getName()).toString();

		// Format the email
		email = new StringBuilder().append(email).append("<br/><br/><br/>").append("Survey link: ").append(surveyLink)
				.append("<br/>").append(currentStudy.getDescription()).append("<br/>")
				.append(currentStudy.getIncentive()).append("<br/>")
				.append("To unsubscribe from future surveys, click the following link: ")
				.append("http://localhost:8080/QualOptServer/services/user/unsubscribe?email="
						+ URLEncoder.encode("experimental1499@gmail.com", "UTF-8") + "/") // testing
																							// with
																							// my
																							// email
				.toString();

		generateMailMessage.setContent(email, "text/html");

		Transport transport = getMailSession.getTransport("smtp");

		// Connect and send the email
		transport.connect("smtp.gmail.com", sender, password);
		transport.sendMessage(generateMailMessage, generateMailMessage.getAllRecipients());
		transport.close();

		return Response.status(Status.ACCEPTED).build();
	}

	/**
	 * Called when the unsubscribe link in the email is clicked
	 * 
	 * @param email
	 * @return
	 * @throws Exception
	 */
	@GET
	@Path("/unsubscribe")
	@Produces(MediaType.APPLICATION_XML)
	@Consumes({ "text/plain,text/html,application/json" })
	public Response unsubscribe(@QueryParam("email") String email) throws Exception {
		email = URLDecoder.decode(email, "UTF-8");
		try {
			// Store the email into the database
			DatabaseConnection database = new DatabaseConnection();
			Connection con = database.getConnection(true);
			String sql = "INSERT INTO UNSUB (email) values (?)";
			PreparedStatement st = con.prepareStatement(sql);
			st.setString(1, email);
			st.execute();
			con.close();
		} catch (Exception e) {
			throw e;
		}
		return Response.status(Status.ACCEPTED).build();
	}

	/**
	 * Called when a participant clicks the survey link.
	 * 
	 * @param surveyLink
	 * @param studyName
	 * @return
	 * @throws Exception
	 */
	@GET
	@Path("/record")
	@Produces(MediaType.APPLICATION_XML)
	@Consumes({ "text/plain,text/html,application/json" })
	public Response recordParticipant(@QueryParam("link") String surveyLink, @QueryParam("name") String studyName)
			throws Exception {

		try {
			// Record data to databse (updating click count for now)
			DatabaseConnection database = new DatabaseConnection();
			Connection con = database.getConnection(true);
			String sql = "UPDATE STUDY SET CLICKCOUNT = CLICKCOUNT + 1 WHERE NAME = (?)";
			PreparedStatement st = con.prepareStatement(sql);
			st.setString(1, studyName);
			st.execute();
			con.close();
		} catch (Exception e) {
			throw e;
		}
		// Make sure link can be converted to URI
		if (!(surveyLink.startsWith("http://") || surveyLink.startsWith("https://"))) {
			surveyLink = "http://" + surveyLink;
		}
		// Redirect to the survey link
		URL url = new URL(surveyLink);
		URI link = url.toURI();
		return Response.seeOther(link).build();
	}

	public void queryForParticipants(int n) {
		String user = "ghtorrent"; // GHTorrent user name
		String host = "web.ghtorrent.org"; // GHTorrent host
		int port = 22;

		try {

			JSch jsch = new JSch();
			// Add my private key
			jsch.addIdentity("MIIEowIBAAKCAQEAztEvxppu+xT06NReA05k8TD3T4AhyVXf9+Rwnn55TsIHvkYj"
					+ "BlFpE4253gLV4ljEndojINbS+acDSsefFEBsyBzK1BSSDTliSf3hQib7YrciG9R2"
					+ "/pTF7PQV/RQAt2FCKPmYJPB4baqvHRPKgdrB6CX+AGmSh4A2+nzWwjSbC5VsWm/X"
					+ "jVRoa6Qusy6UqAj17wbF8zPMLe5vKuRPYCn/a39HQ0QmrLiWITHpYRxWA+L4DAdP"
					+ "RPcIZPRFpSzUhnbZIRD0ZzEGpsMfgE5bprD/f4dCsOuXIj0wlgy5NQJGk3b/UDeY"
					+ "Ip9vktyhRWwjkY/OiLhOTrCcQ6o7O8fE47y0qwIDAQABAoIBAHbZ5Bi/2xNTYcMD"
					+ "d9tyi7PHrffz7HalcQYmM0oB6HiILKb961bQJhBkm/Gns35WAPetyg4vJiLuBYhN"
					+ "229p7pm5Yh4qjBwpZACdc3vupvx9vY48tP1sRan8Qz6i5h58N+cQOIzR3IM5WVTe"
					+ "cLvbGR/t5WAbS2evGOkuIMSOMqOefHGUNpALgMnuXFRlog92InB6ONA9owJJz+W3"
					+ "0oUT3W4kP9JLRGt+ZNTuBHUR3HKNf0GNeKg0pSYJd4taIVayKlgUKESwDlmpCmr1"
					+ "O+ZPP+jHkiLW85crzLLz1UioAhm2m54eNxkHY6oLrbLHWswZbch8ZK2pDHERKzfo"
					+ "yW152+ECgYEA70X5fz1uFAVrx9nx6rT8sc6UoI3+m2xXy8Mt4OgNfzwoPb2P5s47"
					+ "fWriQsdcg9vLiHCQzb8+xtokTNrBOCfeeyoWA/iwyQgA6CQkntia66zWSTZbRGzA"
					+ "9eaMOdJ87Q14rap7SZK2urvTQ3hkm8LQqWAwI1XLES8nVTkrvaAuU1MCgYEA3UZg"
					+ "kMFRrjx9nDBMDMLajSOi2MXjuRxQMJaaMaQL5fQ/FXDpGberp+UnAdAgJFjGMicM"
					+ "IHIKHRAyzrW3zcs7qIsJIS0YV/tlfKbXL2ya6KjyIJL1bFj26+LYW6Cxn0d7Cla5"
					+ "kOwsGo8ET5riprY76s+UyhHNx3rsEHxqx5XBBkkCgYA4kH1D9DzCpOlu7HoBN1oJ"
					+ "msGOFyNakMlMlU6SPal7K7iDp/2N3bE4m/zzNngLf/lkvt+slAp+LfGo7YoCAYLZ"
					+ "8QAVXkREsgys1GaH2sL89fYOhrgau+798suxm64GyEmAHK5anUFvcZmm+J4oKGz1"
					+ "rZSTteN0o4YT4pkRkf2BmwKBgA3cH6ZRhZU9UrzaxZizB895YPTlCEuK+3bfqA7d"
					+ "8KTZtK3aIa+rsoPUtanGaIz+RoPTsE3D9uA1KImMFlQ8m6MF+m9qjLDOHWA+bxIY"
					+ "YmeaVXg23EqKFAVYcybiHN4WMx3Fqt/p+yU6uhFmaTX6Ciy+DdrOXK5XA7xQnrub"
					+ "pLiJAoGBANMoObScNLjW5RccUXZW76B5wHgpqZw5R3TbCDEK8ooPPJClTZdkZkKS"
					+ "N+PJ03qNVDUr4x6QcPB9CymGx2PpWS42VoqdIlcrlDPNTl0/xR5O2EqCnPDku86l"
					+ "TD3Hvqp6hjwRv3IMELSaQtMklaa+QZihcJzcg9noCOiD5NolYlbs");

			com.jcraft.jsch.Session session = jsch.getSession(user, host, port);
			session.setConfig("StrictHostKeyChecking", "no");
			session.setPortForwardingL(3306, host, 3306);
			System.out.println("Establishing Connection...");
			session.connect();

			System.out.println("Connection established.");
			System.out.println("Crating SFTP Channel.");
			ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
			sftpChannel.connect();
			System.out.println("SFTP Channel created.");

			// TODO: GHTorrent query
			DatabaseConnection database = new DatabaseConnection();
			Connection con = database.getConnection(false);
			String sql = "TODO";
			PreparedStatement st = con.prepareStatement(sql);
			st.execute();
			con.close();

		} catch (Exception e) {
			System.err.print(e);
		}

	}

}