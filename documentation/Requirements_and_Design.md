# Requirements and Design

## 1. Change History

| **Change Date** | **Modified Sections**                                                              | **Rationale**                                  |
| --------------- | ---------------------------------------------------------------------------------- | ---------------------------------------------- |
| 2026-10-19      | Project description, documented features, actors, use cases, and high-level design | Filling out the sections for submission to CP1 |
| 2026-10-31      | Everything concerning requirements, features, use cases, components, interfaces and the like (2.0, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7) | Filling out the sections for submission to CP2 |

---

## 2. Project Description

**TradeOff** is a competitive virtual investing game, which lets users trade stocks with virtual money, build portfolios, and compete against friends using real market data. It is intended for students, young adults, investing beginners, and friends who want to compare their results. Investing can feel complicated, risky, and intimidating; TradeOff turns that practice into an interactive game where users can experiment with strategies and learn without risking real money.

> [!WARNING]
> ***REWRITE***: Users can invest individually or compete in private investment challenges with an agreed starting amount of virtual money and a set competition period. Real-time portfolio information and leaderboards help users track investments and compare performance. The central technical challenge is representing real stock-market behavior in a playable virtual trading system while keeping market prices and portfolio values updated together.

---

## 3. Requirements Specification

### **3.1. List of Features**

1. **Authentication** - Registration and signins happen through the Google authentication service, which then links to a personal virtual trading account and profile-specific settings (such as managing account info, app preferences, signing out).
2. **Market** - Live market information retrieved from the Alpaca Market Data API which includes browsing and searching through current stock prices and their changes, with updates being cached ~every 8 seconds.
3. **Trading** - People can buy or sell shares using virtual money through a trading system, which governs validating funds and shares, updating account balances and stock positions, and recording transactions completed.
4. **Portfolio** - View your personal virtual investment portfolio through its respective screen, including available cash, held stocks, total portfolio value, allocation of assets, profits and losses, and transaction history.
5. **Social** - People can send, cancel, accept and decline friend requests between other users, and compare statistics with accepted friends, displaying rankings that can be sorted in a leaderboard.

### **3.2. Use Case Diagram**

> [!WARNING]
> remake so they match the new use cases. preface w/ ! to make it show up here

[Use-case diagram reproduced from M2](images/m2-use-case-diagram.png)

### **3.3. Actors Description**

1. **[primary] Guest (unauthenticated user)** - One who has no TradeOff account. All of the user interface is barred for this user except for market data (which they cannot act on...).
2.  **[primary] Trader (authenticated user)** - One who has a TradeOff account. They can thus act on market data, execute trading, use social features, have a logged history viewable by portfolio, among other app capabilities.
3. **[secondary] Google Authentication Service - Secondary**: Provides account authentication, specifically the verification of TradeOff accounts by linking them to certain Google accounts.
4. **[secondary] Alpaca Market Data API - Secondary**: Bridges TradeOff with necessary stock market data to emulate an investment platform (maybe be more specific?)

### **3.4. Use Case Description**

1. **[authentication] Sign-up/Sign-in**: Authenticating through the Google Sign-in Interface. If the account doesn't have a linked TradeOff account, one will be created and linked for them.
2. **[authentication] Sign-out**: Clears authentication for users who are signed in, clearing the session from their TradeOff account and returning them to an unauthenticated user.
3. **[+] Manage Preferences**: View and edit account and application information, including changing display name and username, or changing application appearance (e.g. light/dark mode). TradeOff will save the changed info accordingly.
4. **[market] Browse Stocks**: Move though available stocks or use a search bar to view stocks by their company name or ticker symbol. TradeOff shows matching stocks and available market info.
5. **[trading] Execute Trade**: Select a stock, specify how much of that stock, and choose to buy or sell using the virtual funds. User balance and shares are updated and the trade is recorded.
6. **[portfolio] Load Portfolio**: View your cash balance, stock holdings, total value, asset allocations, profits and losses, and transaction history. The Portfolio page of the app will present all this information.
7. **[social] Manage Friend Requests**: Send friend requests to other TradeOff users. One can also accept, decline, or cancel pending requests. TradeOff updates itself depending on the response.
8. **[social] Remove Friend**: Users can remove an existing friend from their friend list. TradeOff will take notice of the removal request and will remove info regarding that friendship.
9. **[social] View Leaderboard**: View rankings comparing cash balance or percentage cash profits/lossses between friends. The leaderboard can be reordered or filtered to emphasize a certain criterion.

### **3.5. Formal Use Case Specifications (5 Most Major Use Cases)**

<a name="fucs1"></a>

1. **Browse Stocks**
   - Description: Move though available stocks or use a search bar to view stocks by their company name or ticker symbol. TradeOff shows matching stocks and available market info.
   - Primary Actor(s): Guest, Trader
   - Preconditions:
      - Actor(s) are allowed to open the Market page
   - Postconditions:
      - Actor(s) have viewed available stock information
   - Main Success Scenario:
      - (1) Actor opens the Market page
      - (2) System displays the available stocks and their market information
      - (3) Actor scrolls through the available stocks
      - (4) Actor enters a company name or ticker symbol into the search bar
      - (5) System filters the stock list according to the search input
      - (6) Actor views matching stocks and their displayed market information
   - Some Failure Scenarios:
      - (2a) Market information is unavailable.
         - (2a1) System indicates that the stock information could not be loaded.
         - (2a2) Actor returns to the Market page to attempt loading the information again.
      - (5a) No stocks match the search input.
         - (5a1) System displays no matching results.
         - (5a2) Actor modifies or clears the search input.
         - (5a3) System updates the displayed stock list, returning to step 5.

<a name="fucs2"></a>

2. **Execute Trade**
   - Description: Select a stock, specify how much of that stock, and choose to buy or sell using the virtual funds. User balance and shares are updated and the trade is recorded.
   - Primary Actor(s): Trader
   - Preconditions:
      - Actor(s) are allowed to open the Trading page
   - Postconditions:
      - Trader's virtual cash balance and stock holdings reflect the completed trade
      - The completed trade is recorded in the Trader's transaction history
   - Main Success Scenario:
      - (1) Trader opens the Trading page
      - (2) Trader selects a stock to trade
      - (3) System displays the selected stock and its trading information
      - (4a) Trader chooses to buy shares
         - (4a1) Trader selects the Buy option
         - (4a2) Trader enters the number of shares to purchase
         - (4a3) Trader submits the trade
         - (4a4) System validates the quantity and available virtual cash
         - (4a5) System executes the purchase and updates the Trader's cash balance and stock holdings
         - (4a6) System records the completed transaction and displays the trade result
      - (4b) Trader chooses to sell shares
         - (4b1) Trader selects the Sell option
         - (4b2) Trader enters the number of shares to sell
         - (4b3) Trader submits the sale
         - (4b4) System validates the quantity and available shares
         - (4b5) System executes the sale and updates the Trader's cash balance and stock holdings
         - (4b6) System records the completed transaction and displays the trade result
   - Some Failure Scenarios:
      - (4c) Trader does not own enough shares to complete the sale.
         - (4c1) System rejects the sale and informs the Trader that they have insufficient shares
         - (4c2) Trader reduces the sale quantity and returns to step 4a3, or cancels the trade
      - (7a) Trader has insufficient virtual cash for the purchase.
         - (7a1) System rejects the purchase and informs the Trader that their available balance is insufficient
         - (7a2) Trader reduces the purchase quantity and returns to step 6, or cancels the trade
      - (7b) Trader enters an invalid quantity.
         - (7b1) System rejects the trade and indicates that the entered quantity is invalid
         - (7b2) Trader corrects the quantity and returns to step 6, or cancels the trade

<a name="fucs3"></a>

3. **View Portfolio**
   - Description: View your cash balance, stock holdings, total value, asset allocations, profits and losses, and transaction history. The Portfolio page of the app will present all this information.
   - Primary Actor(s): Trader
   - Preconditions:
      - Actor(s) are allowed to open the Portfolio page
   - Postconditions:
      - Trader has viewed their available portfolio information
      - No changes have been made to the Trader's cash balance or stock holdings
   - Main Success Scenario:
      - (1) Trader opens the Portfolio page
      - (2) System displays the Trader's virtual cash balance and portfolio summary
      - (3) Trader examines their displayed stock holdings
      - (4) System presents the holdings' values and asset allocation
      - (5) Trader examines the displayed total portfolio value and profit/loss information
      - (6) Trader navigates to the transaction history section
      - (7) System displays the Trader's recorded transactions
      - (8) Trader reviews the transaction history
   - Some Failure Scenarios:
      - (3a) Trader does not own any stocks.
         - (3a1) System displays an empty holdings section while continuing to show the Trader's virtual cash balance
         - (3a2) Trader continues reviewing their portfolio or navigates to the Trading page to purchase shares
      - (7a) Trader has no recorded transactions.
         - (7a1) System indicates that no transaction history is available
         - (7a2) Trader returns to the portfolio summary or navigates to the Trading page

<a name="fucs4"></a>

4. **Manage Friend Requests**
   - Description: Send friend requests to other TradeOff users. One can also accept, decline, or cancel pending requests. TradeOff updates itself depending on the response.
   - Primary Actor(s): Trader
   - Preconditions:
      - Trader is authenticated and has a TradeOff account
      - Trader has access to the Social page
   - Postconditions:
      - A new friend request is pending, or an existing request has been accepted, declined, or cancelled
      - Friendship info of the involved actor(s) reflect this
   - Main Success Scenario:
      - (1) Trader opens the Social page
      - (2) Trader selects the Friends section
      - (3) System displays the Trader's existing friends and pending friend requests
      - (4) Trader chooses to add a friend
      - (5) Trader identifies another TradeOff user to send a friend request to
      - (6) Trader submits the friend request
      - (7) System checks whether the requested friendship can be created
      - (8) System creates a pending friend request
      - (9) System updates the displayed request information
   - Some Failure Scenarios:
      - (5a) The requested user does not exist.
         - (5a1) System informs the Trader that the requested user could not be found
         - (5a2) Trader corrects the user information and returns to step 5, or cancels the request
      - (7a) A pending friend request or accepted friendship already exists.
         - (7a1) System prevents the duplicate request and indicates the existing relationship
         - (7a2) Trader returns to the Friends section without creating another request
      - (4a) Trader chooses to accept an incoming friend request instead.
         - (4a1) Trader selects an incoming pending friend request
         - (4a2) Trader chooses Accept
         - (4a3) System updates the relationship to an accepted friendship
         - (4a4) System updates the friends and pending requests lists
      - (4b) Trader chooses to decline an incoming friend request instead.
         - (4b1) Trader selects an incoming pending friend request
         - (4b2) Trader chooses Decline
         - (4b3) System removes the pending friend request
         - (4b4) System updates the pending requests list
      - (4c) Trader chooses to cancel an outgoing friend request instead.
         - (4c1) Trader selects an outgoing pending friend request
         - (4c2) Trader chooses Cancel
         - (4c3) System removes the pending friend request
         - (4c4) System updates the pending requests list

<a name="fucs5"></a>

5. **View Leaderboard**
   - Description: View rankings comparing cash balance or percentage cash profits/lossses between friends. The leaderboard can be reordered or filtered to emphasize a certain criterion.
   - Primary Actor(s): Trader
   - Preconditions:
      - Actor(s) are allowed to open the Social page
   - Postconditions:
      - Trader has viewed the available leaderboard rankings
   - Main Success Scenario:
      - (1) Trader opens the Social page
      - (2) Trader selects the Leaderboard section
      - (3) System retrieves leaderboard information for the Trader and their accepted friends
      - (4) System displays users ranked according to the selected criterion
      - (5) Trader examines the displayed rankings
      - (6) Trader selects a different ranking criterion
      - (7) System reorders the leaderboard according to the selected criterion
      - (8) Trader reviews the updated rankings
   - Some Failure Scenarios:
      - (3a) Trader has no accepted friends.
         - (3a1) System displays the Trader's own leaderboard information without comparisons to friends
         - (3a2) Trader continues viewing their information or navigates to the Friends section to send friend requests
      - (3b) Leaderboard information cannot be retrieved.
         - (3b1) System indicates that leaderboard information is unavailable
         - (3b2) Trader revisits or refreshes the Leaderboard section to retry the request

### **3.6. Screen Mock-ups**

| **Prospective Market screen mockup** | **Prospective Portfolio screen mockup** |
| --- | --- |
| ![3.6-market-mockup](3.6-market-mockup.png) | ![3.6-portfolio-mockup](3.6-portfolio-mockup.png) |

### **3.7. Non-Functional Requirements**

<a name="nfr1"></a>

1. **Response-Time Consistency**
   - **Description**: When tested by a single user, at least 95% of requests to each of TradeOff's three Market API endpoints must complete within 1 second, measured over at least 100 requests per endpoint with the required data cached.
   - **Justification**: TradeOff supports multiple market-data operations, including stock browsing, individual stock lookup, and historical price retrieval. Measuring response times separately for each endpoint ensures that acceptable performance is maintained across these operations rather than allowing faster requests to conceal slower ones.

<a name="nfr2"></a>

2. **Concurrent-Load Efficiency**
   - **Description**: Under a workload of 10 simultaneous simulated users, at least 95% of cached Market API requests must complete within 1 second, with no requests failing due to server overload, measured over at least 100 requests.
   - **Justification**: TradeOff is a multi-user trading application, so its market-data service must remain responsive when users browse stocks simultaneously. The 1-second limit follows the same usability guideline as NFR 1, while 10 concurrent users defines a manageable initial workload for evaluating this project's shared backend rather than claiming production-scale capacity.

<a name="nfr3"></a>

3. **External-Service Reliability**
   - **Description**: After a simulated temporary Alpaca API outage, TradeOff must automatically resume retrieving updated market data within two eligible market-refresh intervals (~16s) after Alpaca becomes available again, without restarting the application or backend.
   - **Justification**: TradeOff depends on Alpaca for market information, making recovery from temporary provider interruptions VERY important. TradeOff's market refreshes happen every 8 seconds, so two refresh periods allows for some lenience, but a window small enough to ensure TradeOff can reliably come back on time.

---

## 4. Designs Specification

### **4.1. Main Components**

> [!WARNING]
> remake so they match what Project Description wants (namely, you need to add interfaces to these!)

| **Component** | **Documented responsibility or connection** |
| --- | --- |
| **User Service** | Handles authentication verification, account creation and retrieval, and user information. Centralizing these responsibilities allows other services to rely on a consistent authenticated user identity. |
| **Social Service** | Manages friend requests, friendships, and leaderboard information stored in the database. Separating social logic keeps relationship management independent of trade execution and market-data retrieval. |
| **Portfolio Service** | Retrieves account holdings and transaction information and is intended to calculate portfolio valuations using market prices. It is defined separately from the Order Engine because reviewing investments and executing trades have different responsibilities. |
| **Order Engine** | Processes virtual buy and sell requests and updates the associated cash balances, holdings, and transaction records. Centralizing trade execution supports consistent validation and database updates. |
| **Market Service** | Retrieves stock information from Alpaca and provides market-data endpoints to the application. Centralizing market-data access avoids requiring each feature to communicate independently with the external provider. |
| **Market Cache** | Stores recently retrieved market information to reduce repeated external API requests. Caching the data saves calling costs, and gives safe data to the Market Service and Portfolio Service. |
| **Market Polling** | Periodically refreshes cached stock information. Though a different block, polling the API is partially built into the Market Service. |
| **Notifier Service** | Planned backend component for sending application notifications through Firebase Cloud Messaging. Separating notification creation from the functional services would allow notification behavior to be managed independently when implemented. |
| **Google Authentication Service** | Provides external identity authentication for TradeOff users. Using Google avoids implementing a separate password-based authentication system while allowing TradeOff to maintain its own application accounts. |
| **Alpaca Market Data API** | Supplies external stock-market information used by TradeOff's Market functionality. This service is imperative because TradeOff requires market information from an external provider rather than maintaining its own market-data source. |
| **Firebase Cloud Messaging** | Delivers application notifications through itself. Separating notification delivery from functional services would allow notification behavior to be managed independently when implemented. |

### **4.2. The Database**

> [!WARNING]
> remake so they match what Project Description wants

1. **MySQL**
   - **Purpose**: Store user accounts, virtual cash balances, holdings, trade history, watchlists, and social relationships. The database will support the Auth, Social, Order Engine, Portfolio, and Workers components.
   - **Rationale**: MySQL supports structured relationships and constraints between users, holdings, and trades. Its transactions allow cash balances, holdings, and trade history to be updated together, preventing partially completed trades.
   - **Access**: Backend components will access MySQL. The frontend will retrieve and modify data through backend interfaces rather than connecting directly to the database.

### **4.3. External Modules**

> [!WARNING]
> remake so they match what Project Description wants

1. **Google Authentication**
   - **Purpose**: Allow users to sign in using their Google accounts. The Auth component will verify Google authentication credentials and associate the verified identity with a user account stored in MySQL.
   - **Rationale**: Provides external authentication without using Firebase Authentication.
2. **Alpaca Market Data API**
   - **Purpose**: Supply stock prices and market information for stock searches, virtual trading, and portfolio valuation. The Poller component will retrieve market data and update the cache.
   - **Rationale**: Real market data allows users to practice virtual investing using prices that reflect actual markets.
3. **Firebase Cloud Messaging (FCM)**
   - **Purpose**: Deliver push notifications, such as stock-price alerts, to users' Android devices through the Notifier component.
   - **Rationale**: The course permits Firebase's push notification functionality. Firebase will not be used for authentication, database management, or other application operations.

### **4.4. Frameworks and Libraries**

> [!WARNING]
> remake so they match what Project Description wants

1. **Jetpack Compose**
   - **Purpose**: Build the Android application's user interface.
   - **Reason**: Required by the course and supports screens that react to changes in application state.

2. **Express.js**
   - **Purpose**: Implement backend API routes and request handling.
   - **Reason**: Provides a straightforward framework for organizing the Node.js/TypeScript backend.

3. **Retrofit and OkHttp**
   - **Purpose**: Handle HTTP communication between the Android application and backend.
   - **Reason**: Provide structured API definitions and configurable network connections.

4. **Android Credential Manager and Google ID**
   - **Purpose**: Support Google account sign-in.
   - **Reason**: Integrate external authentication with Android without using Firebase Authentication.

5. **Socket.IO and ws**
   - **Purpose**: Support real-time communication and WebSocket connections.
   - **Reason**: Allow the system to exchange updates through persistent connections.

6. **Prisma (Planned)**
   - **Purpose**: Define database models, manage migrations, and access MySQL from the backend.
   - **Reason**: Provides typed database queries and transactions for consistent updates.

7. **Firebase Cloud Messaging SDK (Planned)**
   - **Purpose**: Send and receive push notifications.
   - **Reason**: Supports Android notifications and is the Firebase functionality permitted by the course.

8. **Jest and Supertest**
   - **Purpose**: Run automated backend tests and verify HTTP API behavior.
   - **Reason**: Support repeatable testing and coverage measurement.

9. **Compose Testing APIs**
   - **Purpose**: Automate interactions with Android Compose screens.
   - **Reason**: Support verification of user flows through the application.

### **4.5. Dependencies Diagram**

> [!WARNING]
> remake so they match the new components. preface w/ ! to make it show up here

[High-level design reproduced from M2, slide 5](images/m2-high-level-design.png)

### **4.6. Use Case Sequence Diagram (5 Most Major Use Cases)**

> [!WARNING]
> remake so they match the new use cases. preface w/ ! to make them show up here

1. **Buy Shares**

[Buy Shares sequence diagram](images/sequence-buy-shares.png)

2. **Sell Shares**

[Sell Shares sequence diagram](images/sequence-sell-shares.png)

3. **Manage Watchlist**

[Manage Watchlist sequence diagram](images/sequence-manage-watchlist.png)

4. **View Live Portfolio**

[View Live Portfolio sequence diagram](images/sequence-view-live-portfolio.png)

5. **View Leaderboard**

[View Leaderboard sequence diagram](images/sequence-view-leaderboard.png)

### **4.7. Design of Non-Functional Requirements**

> [!WARNING]
> we may need specific implementation to be mentioned here if any

<a name="nfrd1"></a>

1. **Response-Time Consistency**
   - **Implementation**: A performance test will send at least 100 sequential requests to any relevant API endpoints (e.g. market/stocks, stocks/:symbol, and :symbol/history). Response times will be recorded separately for each endpoint, and each must achieve a 95th-percentile response time (i.e. response time 95% of requests fall under) under one second.

<a name="nfrd2"></a>

2. **Concurrent-Load Efficiency**
   - **Implementation**: A load test will simulate 10 users issuing at least 100 requests concurrently against cached Market API endpoints. The test will calculate the 95th-percentile response time (i.e. the time 95% of response times fall under) and check for holdups caused by server overload.

<a name="nfrd3"></a>

3. **External-Service Reliability**
   - **Implementation**: An automated test will simulate an Alpaca outage, restore successful API responses, allow any configured retry cooldown to expire, and verify that updated market data is retrieved within two eligible refresh intervals without restarting either component.
