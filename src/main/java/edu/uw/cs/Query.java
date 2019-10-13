package edu.uw.cs;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * Runs queries against a back-end database
 */
public class Query {
  // DB Connection
  private Connection conn;

  private String user;
  private ItineraryItem[] itineraries;

  // Password hashing parameter constants
  private static final int HASH_STRENGTH = 65536;
  private static final int KEY_LENGTH = 128;

  // Canned queries
  private static final String BEGIN_TRANSACTION = "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE; BEGIN TRANSACTION;";
  private PreparedStatement beginTransactionStatement;

  private static final String COMMIT = "COMMIT TRANSACTION;";
  private PreparedStatement commitTransactionStatement;

  private static final String ROLLBACK = "ROLLBACK TRANSACTION";
  private PreparedStatement rollbackTransactionStatement;

  private static final String CLEAR_TABLES = "TRUNCATE TABLE Reservations DELETE FROM Users DELETE FROM ReservationID" +
          " INSERT INTO ReservationID VALUES (1)";
  private PreparedStatement clearTablesStatement;

  private static final String CHECK_FLIGHT_CAPACITY =
          "SELECT (SELECT capacity FROM Flights WHERE fid = ?) - COUNT(*) AS capacity\n" +
                  "FROM Reservations\n" +
                  "WHERE flight1 = ? OR flight2 = ?";
  private PreparedStatement checkFlightCapacityStatement;

  private static final String GET_LOGIN = "SELECT uname,pwHash, pwSalt FROM Users WHERE uname = ?";
  private PreparedStatement getLoginStatement;

  private static final String CREATE_USER = "INSERT INTO Users VALUES(?,?,?,?)";
  private PreparedStatement createUserStatement;

  private static final String GET_DIRECT_FLIGHTS =
          "SELECT TOP (?) fid,day_of_month,carrier_id,flight_num,origin_city,dest_city,actual_time,capacity,price " +
                  "FROM Flights " +
                  "WHERE origin_city = ? AND dest_city = ? AND day_of_month =  ? AND canceled = 0 " +
                  "ORDER BY actual_time ASC, fid ASC";
  private PreparedStatement getDirectFlightsStatement;

  private static final String GET_INDIRECT_FLIGHTS =
          "SELECT TOP (?) F1.fid AS fid1,F1.day_of_month AS day_of_month1,F1.carrier_id AS carrier_id1,F1.flight_num "
                  +"AS flight_num1,F1.origin_city AS origin_city1,F1.dest_city AS dest_city1,F1.actual_time AS "
                  +"actual_time1,F1.capacity AS capacity1,F1.price AS price1, "
                  +"F2.fid AS fid2,F2.day_of_month AS day_of_month2,F2.carrier_id AS carrier_id2,F2.flight_num AS "
                  +"flight_num2,F2.origin_city AS origin_city2,F2.dest_city AS dest_city2,F2.actual_time AS "
                  +"actual_time2,F2.capacity AS capacity2,F2.price AS price2 "
                  +"FROM Flights F1, Flights F2 "
                  +"WHERE F1.origin_city = ? AND F1.dest_city = F2.origin_city AND F2.dest_city = ? AND "
                  +"F1.canceled = 0 AND F2.canceled = 0 AND F1.day_of_month = ? AND F2.day_of_month = ? "
                  +"ORDER BY F1.actual_time + F2.actual_time ASC, F1.fid ASC, F2.fid ASC";
  private PreparedStatement getIndirectFlightsStatement;

  private static final String GET_SAME_DAY_RESERVATIONS =
          "SELECT COUNT(*) AS totalSame FROM Reservations R, FLIGHTS F1, FLIGHTS F2 " +
                  "WHERE R.uname = ? AND F2.fid = R.flight1 AND F1.fid = ? AND F1.day_of_month = F2.day_of_month";
  private PreparedStatement getSameDayStatement;

  private static final String BOOK_RESERVATION = "INSERT INTO Reservations(rid,paid,uname,flight1,flight2) " +
          "VALUES (?,0,?,?,?)";
  private PreparedStatement bookReservationStatement;

  private static final String UPDATE_NEXT_ID = "UPDATE ReservationID SET rid = rid + 1";
  private PreparedStatement updateNextIDStatement;

  private static final String GET_RESERVATION_COST = "SELECT balance,SUM(price) AS totalCost " +
          "FROM Users,Reservations,FLIGHTS " +
          "WHERE Users.uname = ? AND Users.uname = Reservations.uname AND rid = ? AND paid = 0 AND " +
          "(flight1 = fid OR flight2 = fid)\n" +
          "GROUP BY balance ";
  private PreparedStatement getReservationCostStatement;

  private static final String PAY_RESERVATION = "UPDATE Reservations SET paid = 1 WHERE rid = ?";
  private PreparedStatement payReservationStatement;

  private static final String UPDATE_BALANCE = "UPDATE Users SET balance = ? WHERE uname = ?" ;
  private PreparedStatement updateBalanceStatement;

  private static final String GET_RESERVATIONS =
          "SELECT rid,paid,fid,day_of_month,carrier_id,flight_num,origin_city,dest_city,actual_time,capacity,price "+
                  "FROM Reservations,FLIGHTS " +
                  "WHERE uname = ? AND (flight1 = fid OR flight2 = fid) " +
                  "ORDER BY rid ASC";
  private PreparedStatement getReservationsStatement;

  private static final String GET_CANCELING_INFO = "SELECT paid, price " +
          "FROM Reservations, FLIGHTS " +
          "WHERE uname = ? AND rid = ? AND (flight1 = fid OR flight2 = fid)";
  private PreparedStatement getCancelingInfoStatement;

  private static final String CANCEL_RESERVATION = "DELETE FROM Reservations " +
          "WHERE rid = ?; " +
          "UPDATE Users " +
          "SET balance = balance + ? " +
          "WHERE uname = ?;";
  private PreparedStatement cancelReservationStatement;

  private static final String GET_ID = "SELECT rid FROM ReservationID";
  private PreparedStatement getIDStatement;


  /**
   * Establishes a new application-to-database connection. Uses the
   * dbconn.properties configuration settings
   *
   * @throws IOException
   * @throws SQLException
   */
  public void openConnection() throws IOException, SQLException {
    // Connect to the database with the provided connection configuration
    Properties configProps = new Properties();
    configProps.load(new FileInputStream("dbconn.properties"));
    String serverURL = configProps.getProperty("hw1.server_url");
    String dbName = configProps.getProperty("hw1.database_name");
    String adminName = configProps.getProperty("hw1.username");
    String password = configProps.getProperty("hw1.password");
    String connectionUrl = String.format("jdbc:sqlserver://%s:1433;databaseName=%s;user=%s;password=%s", serverURL,
            dbName, adminName, password);
    conn = DriverManager.getConnection(connectionUrl);

    // By default, automatically commit after each statement
    conn.setAutoCommit(true);

    // By default, set the transaction isolation level to serializable
    conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
  }

  /**
   * Closes the application-to-database connection
   */
  public void closeConnection() throws SQLException {
    conn.close();
  }

  /**
   * Clear the data in any custom tables created.
   * <p>
   * WARNING! Do not drop any tables and do not clear the flights table.
   */
  public void clearTables() {
    try {
      beginTransaction();
      clearTablesStatement.executeUpdate();
      commitTransaction();
    } catch (Exception e) {
      try {
        rollbackTransaction();
        clearTables();
      }
      catch(SQLException err){
      }
    }
  }

  /*
   * prepare all the SQL statements in this method.
   */
  public void prepareStatements() throws SQLException {
    getIDStatement = conn.prepareStatement(GET_ID);
    beginTransactionStatement = conn.prepareStatement(BEGIN_TRANSACTION);
    commitTransactionStatement = conn.prepareStatement(COMMIT);
    rollbackTransactionStatement = conn.prepareStatement(ROLLBACK);
    clearTablesStatement = conn.prepareStatement(CLEAR_TABLES);
    checkFlightCapacityStatement = conn.prepareStatement(CHECK_FLIGHT_CAPACITY);
    getLoginStatement = conn.prepareStatement(GET_LOGIN);
    createUserStatement = conn.prepareStatement(CREATE_USER);
    getDirectFlightsStatement = conn.prepareStatement(GET_DIRECT_FLIGHTS);
    getIndirectFlightsStatement = conn.prepareStatement(GET_INDIRECT_FLIGHTS);
    getSameDayStatement = conn.prepareStatement(GET_SAME_DAY_RESERVATIONS);
    bookReservationStatement = conn.prepareStatement(BOOK_RESERVATION);
    updateNextIDStatement = conn.prepareStatement(UPDATE_NEXT_ID);
    getReservationCostStatement = conn.prepareStatement(GET_RESERVATION_COST);
    payReservationStatement = conn.prepareStatement(PAY_RESERVATION);
    updateBalanceStatement = conn.prepareStatement(UPDATE_BALANCE);
    getReservationsStatement = conn.prepareStatement(GET_RESERVATIONS);
    getCancelingInfoStatement = conn.prepareStatement(GET_CANCELING_INFO);
    cancelReservationStatement = conn.prepareStatement(CANCEL_RESERVATION);
  }

  /**
   * Takes a user's username and password and attempts to log the user in.
   *
   * @param username user's username
   * @param password user's password
   * @return If someone has already logged in, then return "User already logged
   * in\n" For all other errors, return "Login failed\n". Otherwise,
   * return "Logged in as [username]\n".
   */
  public String transaction_login(String username, String password) {
    if (user!=null){
      return "User already logged in\n";
    }
    try {
      beginTransaction();
      getLoginStatement.clearParameters();
      getLoginStatement.setNString(1, username);
      ResultSet results = getLoginStatement.executeQuery();
      if (results.next()) {
        if (Arrays.equals(results.getBytes("pwHash"),createHash(password, results.getBytes("pwSalt")))) {

          user = results.getString("uname");
          itineraries = null;
          commitTransaction();
          return "Logged in as " + user + "\n";
        }
      }
    }
    catch (SQLException e) {
      try {
        commitTransaction();
      } catch(SQLException exc){
      }
    }
    return "Login failed\n";
  }

  /**
   * Implement the create user function.
   *
   * @param username   new user's username. User names are unique the system.
   * @param password   new user's password.
   * @param initAmount initial amount to deposit into the user's account, should
   *                   be >= 0 (failure otherwise).
   * @return either "Created user {@code username}\n" or "Failed to create user\n"
   * if failed.
   */
  public String transaction_createCustomer(String username, String password, int initAmount) {
    if (initAmount < 0) {
      return "Failed to create user\n";
    }
    try {
      beginTransaction();
      createUserStatement.clearParameters();
      createUserStatement.setNString(1, username);
      createUserStatement.setInt(4, initAmount);
      // Generate a random cryptographic salt
      SecureRandom random = new SecureRandom();
      byte[] salt = new byte[16];
      random.nextBytes(salt);
      createUserStatement.setBytes(3, salt);
      createUserStatement.setBytes(2, createHash(password, salt));
      createUserStatement.executeUpdate();
      commitTransaction();
      return "Created user " + username + "\n";
    } catch (SQLException e) {
      try {
        rollbackTransaction();
      }
      catch(SQLException err){
      }
      return "Failed to create user\n";
    }
  }

  /**
   * Implement the search function.
   * <p>
   * Searches for flights from the given origin city to the given destination
   * city, on the given day of the month. If {@code directFlight} is true, it only
   * searches for direct flights, otherwise is searches for direct flights and
   * flights with one "hop" Only searches for up to the number of itineraries
   * given by {@code numberOfItineraries}.
   * <p>
   * The results are sorted based on total flight time.
   *
   * @param originCity
   * @param destinationCity
   * @param directFlight        if true, then only search for direct flights,
   *                            otherwise include indirect flights as well
   * @param dayOfMonth
   * @param numberOfItineraries number of itineraries to return
   * @return If no itineraries were found, return "No flights match your
   * selection\n". If an error occurs, then return "Failed to search\n".
   * <p>
   * Otherwise, the sorted itineraries printed in the following format:
   * <p>
   * Itinerary [itinerary number]: [number of flights] flight(s), [total
   * flight time] minutes\n [first flight in itinerary]\n ... [last flight
   * in itinerary]\n
   * <p>
   * Each flight should be printed using the same format as in the
   * {@code Flight} class. Itinerary numbers in each search should always
   * start from 0 and increase by 1.
   * @see Flight#toString()
   */
  public String transaction_search(String originCity, String destinationCity, boolean directFlight, int dayOfMonth,
                                   int numberOfItineraries) {


    StringBuffer sb = new StringBuffer();
    itineraries = new ItineraryItem[numberOfItineraries];
    try {
      beginTransaction();
      //direct results
      getDirectFlightsStatement.clearParameters();
      getDirectFlightsStatement.setInt(1, numberOfItineraries);
      getDirectFlightsStatement.setNString(2, originCity);
      getDirectFlightsStatement.setNString(3, destinationCity);
      getDirectFlightsStatement.setInt(4, dayOfMonth);
      ResultSet directResults = getDirectFlightsStatement.executeQuery();
      commitTransaction();
      int count = 0;
      PriorityQueue<ItineraryItem> queue = new PriorityQueue<>(
              Comparator.comparing(ItineraryItem::getTotalDuration).thenComparing(ItineraryItem::getFlightID1)
                      .thenComparing(ItineraryItem::getFlightID2));
      while (directResults.next()) {
        Flight curr = new Flight();
        curr.fid = directResults.getInt("fid");
        curr.dayOfMonth = directResults.getInt("day_of_month");
        curr.carrierId = directResults.getString("carrier_id");
        curr.flightNum = directResults.getString("flight_num");
        curr.originCity = directResults.getString("origin_city");
        curr.destCity = directResults.getString("dest_city");
        curr.time = directResults.getInt("actual_time");
        curr.capacity = directResults.getInt("capacity");
        curr.price = directResults.getInt("price");
        queue.add(new ItineraryItem(curr));
        count++;
      }
      directResults.close();
      //if not enough direct flights and allowed indirect flights, then gets indirect flights
      if (!directFlight && count < numberOfItineraries) {
        beginTransaction();
        getIndirectFlightsStatement.clearParameters();
        getIndirectFlightsStatement.setInt(1, numberOfItineraries - count);
        getIndirectFlightsStatement.setNString(2, originCity);
        getIndirectFlightsStatement.setNString(3, destinationCity);
        getIndirectFlightsStatement.setInt(4, dayOfMonth);
        getIndirectFlightsStatement.setInt(5, dayOfMonth);
        ResultSet indirectResults = getIndirectFlightsStatement.executeQuery();
        commitTransaction();
        while (indirectResults.next()) {
          //first flight of current itinerary
          Flight curr1 = new Flight();
          curr1.fid = indirectResults.getInt("fid1");
          curr1.dayOfMonth = indirectResults.getInt("day_of_month1");
          curr1.carrierId = indirectResults.getString("carrier_id1");
          curr1.flightNum = indirectResults.getString("flight_num1");
          curr1.originCity = indirectResults.getString("origin_city1");
          curr1.destCity = indirectResults.getString("dest_city1");
          curr1.time = indirectResults.getInt("actual_time1");
          curr1.capacity = indirectResults.getInt("capacity1");
          curr1.price = indirectResults.getInt("price1");
          //second flight of current itinerary
          Flight curr2 = new Flight();
          curr2.fid = indirectResults.getInt("fid2");
          curr2.dayOfMonth = indirectResults.getInt("day_of_month2");
          curr2.carrierId = indirectResults.getString("carrier_id2");
          curr2.flightNum = indirectResults.getString("flight_num2");
          curr2.originCity = indirectResults.getString("origin_city2");
          curr2.destCity = indirectResults.getString("dest_city2");
          curr2.time = indirectResults.getInt("actual_time2");
          curr2.capacity = indirectResults.getInt("capacity2");
          curr2.price = indirectResults.getInt("price2");

          queue.add(new ItineraryItem(curr1,curr2));
          count++;
        }
      }
      for(int i = 0; !queue.isEmpty(); i++){
        itineraries[i] = queue.remove();
        sb.append("Itinerary " + i + ": " + itineraries[i].size() + " flight(s), " +
                itineraries[i].totalDuration + " minutes\n");
        sb.append(itineraries[i]);
      }
      if (sb.length() == 0) {
        return "No flights match your selection\n";
      } else {
        return sb.toString();
      }
    }
    catch (SQLException e) {
      try {
        commitTransaction();
      } catch (SQLException err){
      }
      return transaction_search(originCity, destinationCity, directFlight, dayOfMonth, numberOfItineraries);
    }

  }

  /**
   * Implements the book itinerary function.
   *
   * @param itineraryId ID of the itinerary to book. This must be one that is
   *                    returned by search in the current session.
   * @return If the user is not logged in, then return "Cannot book reservations,
   * not logged in\n". If try to book an itinerary with invalid ID, then
   * return "No such itinerary {@code itineraryId}\n". If the user already
   * has a reservation on the same day as the one that they are trying to
   * book now, then return "You cannot book two flights in the same
   * day\n". For all other errors, return "Booking failed\n".
   * <p>
   * And if booking succeeded, return "Booked flight(s), reservation ID:
   * [reservationId]\n" where reservationId is a unique number in the
   * reservation system that starts from 1 and increments by 1 each time a
   * successful reservation is made by any user in the system.
   */
  public String transaction_book(int itineraryId) {
    if (user == null) {
      return "Cannot book reservations, not logged in\n";
    } else if (itineraries == null || itineraryId > itineraries.length) {
      return "No such itinerary " + itineraryId + "\n";
    }
    try {
      beginTransaction();
      getSameDayStatement.clearParameters();
      getSameDayStatement.setNString(1, user);
      getSameDayStatement.setInt(2, itineraries[itineraryId].flight1.fid);
      ResultSet result = getSameDayStatement.executeQuery();
      if(!result.next()){
        commitTransaction();
        return "Booking failed\n";
      }
      else if(result.getInt("totalSame") > 0){
        commitTransaction();
        return "You cannot book two flights in the same day\n";
      }
      else{
        int fid1 = itineraries[itineraryId].flight1.fid;
        Flight flight2 = itineraries[itineraryId].flight2;

        //check capacity of both flights
        if(checkFlightCapacity(fid1) <= 0 || (flight2 != null && checkFlightCapacity(flight2.fid) <= 0)){
          commitTransaction();
          return "Booking failed\n";
        }
        bookReservationStatement.clearParameters();
        bookReservationStatement.setNString(2, user);
        bookReservationStatement.setInt(3,fid1);
        //checks if there is a second flight and if not, sets that parameter to null
        if(flight2 == null){
          bookReservationStatement.setNull(4,Types.INTEGER);
        }
        else {
          bookReservationStatement.setInt(4, flight2.fid);
        }
        ResultSet nextIdResults = getIDStatement.executeQuery();
        int nextId;
        if(nextIdResults.next()){
          nextId = nextIdResults.getInt("rid");
          updateNextIDStatement.executeUpdate();
        }
        else{
          rollbackTransaction();
          return "Booking failed\n";
        }
        bookReservationStatement.setInt(1,nextId);
        bookReservationStatement.executeUpdate();
        commitTransaction();
        return "Booked flight(s), reservation ID: " + nextId + "\n";
      }
    }
    catch (SQLException e){
      try {
        rollbackTransaction();
      }
      catch(SQLException err){
      }
      return transaction_book(itineraryId);
    }
  }

  /**
   * Implements the pay function.
   *
   * @param reservationId the reservation to pay for.
   * @return If no user has logged in, then return "Cannot pay, not logged in\n"
   * If the reservation is not found / not under the logged in user's
   * name, then return "Cannot find unpaid reservation [reservationId]
   * under user: [username]\n" If the user does not have enough money in
   * their account, then return "User has only [balance] in account but
   * itinerary costs [cost]\n" For all other errors, return "Failed to pay
   * for reservation [reservationId]\n"
   * <p>
   * If successful, return "Paid reservation: [reservationId] remaining
   * balance: [balance]\n" where [balance] is the remaining balance in the
   * user's account.
   */
  public String transaction_pay(int reservationId) {
    if (user == null) {
      return "Cannot pay, not logged in\n";
    }
    try {
      beginTransaction();
      getReservationCostStatement.clearParameters();
      getReservationCostStatement.setNString(1, user);
      getReservationCostStatement.setInt(2, reservationId);
      ResultSet results = getReservationCostStatement.executeQuery();
      if (results.next()) {
        int cost = results.getInt("totalCost");
        int balance = results.getInt("balance");
        results.close();
        if(cost > balance){
          commitTransaction();
          return "User has only " + balance + " in account but itinerary costs " + cost + "\n";
        }
        else{
          payReservationStatement.clearParameters();
          payReservationStatement.setInt(1,reservationId);
          payReservationStatement.executeUpdate();
          updateBalanceStatement.clearParameters();
          updateBalanceStatement.setInt(1,balance - cost);
          updateBalanceStatement.setNString(2, user);
          updateBalanceStatement.executeUpdate();
          commitTransaction();
          return "Paid reservation: " + reservationId + " remaining balance: "+ (balance - cost) + "\n";
        }
      } else {
        commitTransaction();
        return "Cannot find unpaid reservation " + reservationId + " under user: " + user + "\n";
      }
    } catch (SQLException e) {
      try {
        rollbackTransaction();
      }
      catch(SQLException err){
      }
      return transaction_pay(reservationId);
    }
  }

  /**
   * Implements the reservations function.
   *
   * @return If no user has logged in, then return "Cannot view reservations, not
   * logged in\n" If the user has no reservations, then return "No
   * reservations found\n" For all other errors, return "Failed to
   * retrieve reservations\n"
   * <p>
   * Otherwise return the reservations in the following format:
   * <p>
   * Reservation [reservation ID] paid: [true or false]:\n" [flight 1
   * under the reservation] [flight 2 under the reservation] Reservation
   * [reservation ID] paid: [true or false]:\n" [flight 1 under the
   * reservation] [flight 2 under the reservation] ...
   * <p>
   * Each flight should be printed using the same format as in the
   * {@code Flight} class.
   * @see Flight#toString()
   */
  public String transaction_reservations() {
    if(user == null){
      return "Cannot view reservations, not logged in\n";
    }
    try {
      beginTransaction();
      StringBuffer sb = new StringBuffer();
      getReservationsStatement.clearParameters();
      getReservationsStatement.setNString(1,user);
      ResultSet results = getReservationsStatement.executeQuery();
      commitTransaction();
      int currRid = -1;
      while(results.next()){
        if(currRid != results.getInt("rid")){
          currRid = results.getInt("rid");
          sb.append("Reservation " + currRid + " paid: " + results.getBoolean("paid") + ":\n");
        }
        Flight f = new Flight();
        f.fid = results.getInt("fid");
        f.dayOfMonth = results.getInt("day_of_month");
        f.carrierId = results.getString("carrier_id");
        f.flightNum = results.getString("flight_num");
        f.originCity = results.getString("origin_city");
        f.destCity = results.getString("dest_city");
        f.time = results.getInt("actual_time");
        f.capacity = results.getInt("capacity");
        f.price = results.getInt("price");
        sb.append(f + "\n");
      }
      return sb.toString();
    }
    catch (SQLException e){
      try {
        commitTransaction();
      } catch (SQLException err){

      }
      return transaction_reservations();
    }
  }

  /**
   * Implements the cancel operation.
   *
   * @param reservationId the reservation ID to cancel
   * @return If no user has logged in, then return "Cannot cancel reservations,
   * not logged in\n" For all other errors, return "Failed to cancel
   * reservation [reservationId]\n"
   * <p>
   * If successful, return "Canceled reservation [reservationId]\n"
   * <p>
   * Even though a reservation has been canceled, its ID should not be
   * reused by the system.
   */
  public String transaction_cancel(int reservationId) {
    if (user == null) {
      return "Cannot cancel reservations, not logged in\n";
    }
    try {
      beginTransaction();
      getCancelingInfoStatement.clearParameters();
      getCancelingInfoStatement.setNString(1, user);
      getCancelingInfoStatement.setInt(2, reservationId);
      ResultSet results = getCancelingInfoStatement.executeQuery();
      int refund = 0;
      boolean empty = true;
      while (results.next()) {
        if (results.getBoolean("paid")) {
          refund += results.getInt("price");
        }
        empty = false;
      }
      if (empty) {
        commitTransaction();
        return "Failed to cancel reservation " + reservationId + "\n";
      } else {
        cancelReservationStatement.clearParameters();
        cancelReservationStatement.setInt(1, reservationId);
        cancelReservationStatement.setInt(2, refund);
        cancelReservationStatement.setNString(3, user);
        cancelReservationStatement.executeUpdate();
        commitTransaction();
        return "Canceled reservation " + reservationId + "\n";
      }
    } catch (SQLException e) {
      try {
        rollbackTransaction();
      }
      catch(SQLException err){
      }
      return transaction_cancel(reservationId);
    }
  }

  private byte[] createHash(String password, byte[] salt) {

    // Specify the hash parameters
    KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_STRENGTH, KEY_LENGTH);

    // Generate the hash
    SecretKeyFactory factory = null;
    byte[] hash = null;
    try {
      factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
      hash = factory.generateSecret(spec).getEncoded();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
      throw new IllegalStateException();
    }
    return hash;

  }

  /**
   * Example utility function that uses prepared statements
   */
  private int checkFlightCapacity(int fid) throws SQLException {
    checkFlightCapacityStatement.clearParameters();
    checkFlightCapacityStatement.setInt(1, fid);
    checkFlightCapacityStatement.setInt(2, fid);
    checkFlightCapacityStatement.setInt(3, fid);
    ResultSet results = checkFlightCapacityStatement.executeQuery();
    results.next();
    int capacity = results.getInt("capacity");
    results.close();
    return capacity;
  }

  public void beginTransaction() throws SQLException {
    conn.setAutoCommit(false);
    beginTransactionStatement.executeUpdate();
  }

  public void commitTransaction() throws SQLException {
    commitTransactionStatement.executeUpdate();
    conn.setAutoCommit(true);
  }

  public void rollbackTransaction() throws SQLException {
    rollbackTransactionStatement.executeUpdate();
    conn.setAutoCommit(true);
  }

  private class ItineraryItem {
    public Flight flight1;
    public Flight flight2;
    public int totalDuration;

    public ItineraryItem(Flight flight1, Flight flight2) {
      this.flight1 = flight1;
      this.flight2 = flight2;
      totalDuration = flight1.time;
      if(flight2 != null){
        totalDuration += flight2.time;
      }
    }

    public ItineraryItem(Flight flight1) {
      this(flight1, null);
    }
    public int size(){
      if(flight2 == null){
        return 1;
      }
      else {
        return 2;
      }
    }

    public int getTotalDuration() {
      return totalDuration;
    }
    @Override
    public String toString() {
      String results = flight1.toString() + "\n";
      if(flight2 != null){
        results += flight2.toString() + "\n";
      }
      return results;
    }

    public int getFlightID1() {
      return flight1.fid;
    }

    public int getFlightID2() {
      return flight2.fid;
    }
  }

  /**
   * A class to store flight information.
   */
  class Flight {
    public int fid;
    public int dayOfMonth;
    public String carrierId;
    public String flightNum;
    public String originCity;
    public String destCity;
    public int time;
    public int capacity;
    public int price;

    @Override
    public String toString() {
      return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: " + flightNum + " Origin: "
              + originCity + " Dest: " + destCity + " Duration: " + time + " Capacity: " + capacity + " Price: " + price;
    }
  }
}
