# Bluetooth Communication Protocol Analysis

ClientChannel (or Server) objects represent a connection between our phone and another device (there is one and only one channel for each connected device). They have the following functions:

- Contain data about the connected device (Peer)
- Implement actual communication with a Peer (sending and receiving messages, data, and information)
- Implement the separation and reconstruction of long messages

BluetoothConnectionClient (or Server) objects have the following functions:

- Contain all Client (or Server) type channels
- Act as middleware for sending commands to Client (or Server) type channels, for example for sending messages
- Contain the callback that will be set on all Client (or Server) type channels. Through this, we will receive notifications about connection status and data received from each channel (in the callback we identify the involved channel through the BluetoothGatt object, which we receive in every callback method - this contains the Device with which we will identify the channel)
- Through the callback, BluetoothConnectionClient (or Server) acts as middleware for receiving information from Client (or Server) type channels

The BluetoothCommunicator object has the following functions:

- Contains BluetoothConnectionClient and BluetoothConnectionServer
- Acts as middleware between our app and BluetoothConnectionClient/Server, allowing sending/receiving data with various Peers (i.e., with the channel associated with each peer) without needing to know whether it's a client or server
- Provides a simple interface for device discovery and connection, handling their management (including automatic reconnection management, which is internally managed by this object) (here the bluetooth reset is also implemented in case of errors to force a disconnection)
- Contains and manages the queue of messages to send
</br></br>

## Protocol Analysis

So far we have analyzed the functioning of the bluetooth communication implementation and its architecture. Now we will analyze its communication protocol.

### Advertising and Discovery Protocol Analysis

We can start advertising and discovery separately.

Advertising is a classic BLE advertising but where the bluetooth name will be the name with which we want other devices to find us (formed only by UTF characters) + 2 random UTF characters that will be saved on the device (the same ones will always be used). These characters will be used as a device identifier along with the name, to handle cases where there are multiple devices with the same name and to identify a device over time (unless it is renamed when we are not connected). The device's bluetooth name is modified with the unique custom name when we start advertising and restored to the original device name when we stop it.

Discovery is a classic BLE discovery, but using the unique name (name + 2 random characters) as an identifier. Discovery returns objects called Peer that contain:

- The device name
- The unique device name
- The BluetoothDevice representing the device, which will contain all the information necessary for Android APIs to connect and manage the bluetooth device
- Indications about the status of any connection:
  - `isHardwareConnected` (indicates if the device is physically connected, if false and isConnected is true then the device is reconnecting, if true and isConnected is false then it is disconnecting (if isDisconnecting is true) or in connection request phase)
  - `isConnected`
  - `isReconnecting`
  - `isRequestingReconnection`
  - `isDisconnecting`
</br></br>

### Connection Initialization Protocol Analysis (Handshake)

Once we have found a list of Peers, we can decide to connect to one of them. The handshake process will be as follows:

1. The peer is added to a connection queue called pendingConnections in BluetoothConnectionClient, which will execute connection requests one at a time (from sending the request to its success or failure).

2. Once previous connection requests are finished, the normal Bluetooth LE connection request is sent (connectGatt).

3. The **server** (the other phone) automatically accepts the connection request (this is how Bluetooth LE works on Android) and BluetoothConnectionServer is notified (via onConnectionStateChange -> STATE_CONNECTED). At this point, we create and save, in the channel list, a new ServerChannel representing the connection that will contain a Peer with only:
   - The BluetoothDevice representing the connected device
   - The following status information:
     - `isHardwareConnected: true`
     - `isConnected: false`
     - `isReconnecting: false`
     - `isRequestingReconnection: false`
     - `isDisconnecting: false`
   
   It also starts an internal timer in the channel of 10 seconds. If the connection is not completed by the end of the timer, we will start the disconnection process.

4. The **client**'s BluetoothConnectionClient is notified (via onConnectionStateChange -> STATE_CONNECTED) of the established connection and also creates and adds to other channels a ClientChannel representing the connection that will contain a Peer, taken from pendingConnections, containing:
   - The device name
   - The unique device name
   - The BluetoothDevice representing the connected device
   - The following status information:
     - `isHardwareConnected: true`
     - `isConnected: false`
     - `isReconnecting: false`
     - `isRequestingReconnection: false`
     - `isDisconnecting: false`

   **Note:** Both the device name and unique name are encoded with UTF-8 (to consume only 1 byte per character), so they only support characters supported by this format.

   Like the server, it starts an internal timer in the channel of 10 seconds. If the connection is not completed by the end of the timer, we will start the disconnection process.

   Finally, it sends the service discovery request (discoverServices) (The server has only one service, we will see its structure in the next paragraph).

5. The **server** automatically responds to the service discovery request (handled by the Android OS, which sends the information about the service that the server declared (with bluetoothGattServer.addService(service)) during the creation of BluetoothConnectionServer. This declaration usually happens at app startup, before advertising and only once, as there is only one instance of BluetoothConnectionServer).

6. The **client** (in BluetoothConnectionClient) receives information about the service and its characteristics. At this point, it sets up notification reception on the MTU_RESPONSE characteristic and sends 247 bytes of data to the server via MTU_REQUEST (we will see details of how this type of communication works in the next paragraph).

7. The **server** (in BluetoothConnectionServer) receives the message (in onCharacteristicWriteRequest -> MTU_REQUEST), reads the message byte count and sends it as a response to the client via MTU_RESPONSE (if the current MTU is too low, the message length will be less than 247 bytes).

8. The **client** (in BluetoothConnectionClient) reads the message length reported by MTU_RESPONSE, and if it's < SUB_MESSAGES_LENGTH + 8 (200) then it sends a request for an increased MTU to 247 bytes, otherwise it calls the onMTUChanged callback.

   - If we send the MTU request, the server will accept or reject it automatically and won't do anything else (onMTUChanged is also called on the server, but we don't do anything there) and then the onMTUChanged callback of BluetoothConnectionClient will be called (whether rejected or accepted). If we don't send the request, as already mentioned, we call onMTUChanged ourselves from the client.

   **Note:** Actually, at the moment the client sends 128 bytes to the server, not a number of bytes equal to the MTU needed (247 bytes), but this is an error I just noticed (since this way we always send the MTU change request), so it will be corrected soon.

9. The **client** (in BluetoothConnectionClient) now (in onMTUChanged) sets up notification reception for all other server characteristics (which we will see in the next paragraph) and, through the channel, sends a connection request by sending its unique name to the server via the CONNECTION_REQUEST characteristic.

10. The **server** (in BluetoothConnectionServer) receives the message (in onCharacteristicWriteRequest -> CONNECTION_REQUEST), reads the unique name, inserts it into the Peer of the Channel dedicated to the connection with that client. Finally, it notifies the program that uses the library, using a callback, that a connection request has arrived (which can let the user choose whether to accept it or not, or choose programmatically). At this point, there are 2 options:

    - If you choose to reject the connection, the server (in BluetoothConnectionServer), through the dedicated channel, sends value 1 to the client via the CONNECTION_RESPONSE characteristic, which will notify the program of the rejection and start the disconnection process (which we will see later).
    
    - If you choose to accept the connection, the server (in BluetoothConnectionServer), through the dedicated channel, sends value 0 to the client via the CONNECTION_RESPONSE characteristic and sets the isConnected parameter of the channel's Peer to true.

11. The **client** (in BluetoothConnectionClient) receives the acceptance message (in onCharacteristicWriteRequest -> CONNECTION_RESPONSE), sets the isConnected parameter of the channel's Peer to true and removes said peer from pendingConnections, then executes any other connections present in that list, repeating the steps seen in this paragraph.

Now the two devices are connected and can start exchanging messages.

**Note:** Even though the connection happens physically, the real connection only happens later because this way the server can decide whether to accept or reject the connection.
</br></br>

### Message Sending Analysis (and other data)

#### Sending information via characteristics:

The **server** has only one service. The service characteristics are as follows (divided by categories):

**Connection:**
- CONNECTION_REQUEST
- CONNECTION_RESPONSE
- MTU_REQUEST
- MTU_RESPONSE

**Data exchange:**
- MESSAGE_SEND: Used to send messages from server to client
- DATA_SEND: Used to send bytes from server to client
- MESSAGE_RECEIVE: Used to send messages from client to server
- DATA_RECEIVE: Used to send bytes from client to server
- READ_RESPONSE_MESSAGE_RECEIVED: Used to send reception confirmations of a message from the client (used to confirm to the server that its message arrived correctly, we'll see why below)
- READ_RESPONSE_DATA_RECEIVED: Used to send reception confirmations of bytes from the client (used to confirm to the server that its message arrived correctly, we'll see why below)
- NAME_UPDATE_SEND: Used to send name updates from server to client in case of a bluetooth name rename
- NAME_UPDATE_RECEIVE: Used to send name updates from client to server in case of a bluetooth name rename

**Reconnection:**
- CONNECTION_RESUMED_SEND
- CONNECTION_RESUMED_RECEIVE

**Disconnection:**
- DISCONNECTION_SEND
- DISCONNECTION_RECEIVE

In general, to send messages via any of these characteristics, the procedure (without going into programming details) is as follows (dictated mainly by how the BLE protocol and Android BLE APIs works):

The server, during the creation of BluetoothConnectionServer, which usually happens at app startup (anyway before advertising and only once, as there is only one instance of BluetoothConnectionServer), declares the service (bluetoothGattServer.addService(service)), specifying its characteristics. More precisely, each one is defined by:

- A UUID that acts as the characteristic ID
- The specification of permissions and properties for reading/writing the characteristic (which we won't see here)

As we said in the connection phase, the client requests service information from the server, which provides them automatically (managed by Android). From that moment on, the client can request info about the service and characteristics, necessary to send read and write requests for those characteristics, using bluetoothGatt.getService (bluetoothGatt is an object representing a connection between two devices, we have one saved in each channel). Also in this phase, the client sets the characteristics for which it wants to receive notifications in case of writing by the server.

Writing and reading characteristics is the method by which the client and server exchange data. Usually each characteristic is dedicated to being written either by the client (to send data to the server) or by the server (to send data to the client), so for bidirectional communication at least 2 characteristics are always used for each type of parallel communication.

**Note:** Technically we could use a single characteristic for bidirectional communication, but if both client and server try to write the same characteristic there could be problems, as each characteristic is designed to be written sequentially (one device writes it, the other reads it, and only then should it be rewritten). This is also why a pair of characteristics is used for each type of communication if we want different types of data to be exchanged simultaneously without problems (for example sending messages, bytes, connection status information and exchanging various metadata).

To write to a characteristic, and thus send data to the other device, the client and server have 2 different techniques, let's see both:

**Client:**
When a client wants to write to a characteristic to send data to the server it's connected to:

- The client calls the Android method: writeCharacteristic, passing the UUID that identifies the characteristic and the value (in bytes) we want to write. This way the client sends a write request to the server.
- The server receives the write request and the channel of the connection with that client calls the onCharacteristicWriteRequest method of the callback present in BluetoothConnectionServer, from which we can process the message and send a reception confirmation with the data we want.
- Additionally, the client receives the reception confirmation from the channel of that connection in the onCharacteristicWrite method of the callback present in BluetoothConnectionClient.

**Server:**
When a server wants to write to a characteristic to send data to the client it's connected to (if the client has set up notification reception for that characteristic):

- The server calls the Android method: notifyCharacteristicChanged, passing the UUID that identifies the characteristic and the new value (in bytes) of the characteristic. This way the server sends the notification of the characteristic data change and the new characteristic data.
- The client receives the notification and the channel of the connection with that client calls the onCharacteristicWrite method of the callback present in BluetoothConnectionServer, from which we can process the message.
- Additionally, if in notifyCharacteristicChanged we specify that we want a confirmation (via a parameter), the server receives the reception confirmation from the channel of that connection in the onNotificationSent method of the callback present in BluetoothConnectionServer.

**Note:** The reception confirmation in communications from server to client is slightly different from that from client to server. The confirmation sent by the client is automatically managed by Android BLE APIs, it doesn't respond with data and doesn't have the ability to send a negative response. So in cases where we want to send a response message, not just a reception confirmation (i.e., in sending messages and data (bytes)), we will implement them through a characteristic dedicated to notifying the server of data reception. In the case of messages or data, we will use both confirmations (the automatic one will only be used to identify any sending errors).

#### Message Sending:

Now let's see how message exchange works (for data (bytes) exchange the principles are the same):

First, let's examine the structure of messages that will be sent and received to and from the program using the bluetooth library. These are objects (of type Message) that have the following attributes:

- **Sender**: A Peer type object containing information about the Peer that sent the message (this information is for received messages, so if we're sending a message this field can have null value)
- **Receiver**: A Peer type object containing information about the Peer we want to send the message to. If this field has null value, then the message will be sent to all Peers connected to the current device (i.e., to all channels)
- **Header**: A single character that can be used by the program to identify the message type
- **Data**: A byte array representing the actual content of the message

**Note:** Native support for sending a message to a specific series of Peers is not supported at the moment. You can send a message either to a single Peer or to all connected ones. However, we can emulate sending to a specific series of peers by sending one message at a time to one Peer at a time via the BluetoothCommunicator.

We can send a message via the sendMessage method of BluetoothCommunicator (passing the Message object). When this happens, the message is added to a queue called pendingMessages within the BluetoothCommunicator. Through this queue, we send one message at a time (we wait for the complete sending of a message, including reception confirmation, before sending the next one).

When it's the turn to send a message, the sendMessage method of BluetoothConnectionClient is called (passing the message). When the message has been sent, we call the sendMessage method of BluetoothConnectionServer.

**Note:** If the message doesn't need to be sent to any peer of the BluetoothConnectionClient or Server, they will immediately call the callback that notifies the sending of the message that was provided to them.

**Sending the message via BluetoothConnectionClient (and Server):**

When the message is sent via BluetoothConnectionClient (and Server), it checks the Receiver of the message and filters based on it the channels that must send the message (comparing the uniqueName of the message's Peer with that of each channel's Peer). If there are 0, then it finishes (notifying the upper level of the end of sending), otherwise it calls the writeMessage method (passing the message) of one channel at a time among those to which we must send the message (as in the level above, it sends to the next channel only after the complete sending of the message by the previous channel). Now let's see how message sending works through the channel:

The Channel first divides the message into different subMessages of 192 total bytes (if the message size is smaller than this, then we'll have a simple conversion). These subMessages are objects of type BluetoothMessage, i.e., objects composed of the following attributes:

- **sender**: The sender Peer of the original message
- **id**: An identifier of the original message (the same for all subMessages). It's a SequenceNumber type object containing a 4-byte UTF-8 string (4 characters)
- **sequenceNumber**: The sequence number of the subMessage. When we divide the message into subMessages, we assign an incremental value to each subMessage that identifies its order for reconstructing the original message. It's a SequenceNumber type object containing a 3-byte UTF-8 string (3 characters), but, as we'll see, it can be considered as an integer value ranging from 0 (for the first subMessage) to the number of subMessages we divided the message into (for the last subMessage). Its maximum value is 16 million
- **type**: An integer indicating the message type, FINAL (2) for the last subMessage of the message or NON_FINAL (1) for all previous subMessages
- **data**: The actual data of the subMessage (part of the original message's data) with the first byte being the header of the original message (which is therefore replicated in the data of all subMessages of the original message)

Now let's briefly analyze SequenceNumber type objects:

It's a type of object that wraps a string (the number of characters can be variable) representing a sequence. Through this wrapper, we can add a value, verify if the value is the maximum for that string (based on the number of characters) and compare the value with that of other SequenceNumber type objects, so in practice it allows treating a string as an integer.

To generate a unique id for each complete message (unique with respect to all messages sent from our Peer to the Peer to which the channel is connected), each channel has among its parameters a SequenceNumber type object called messageId. This object is incremented before sending each message and assigned to each subMessage (so the id of messages sent by a channel is incremental).

Once the message is divided into various subMessages, we save them in the channel's queue called pendingMessage, then we execute the writeSubMessage method, which executes (in a separate thread), one at a time, the sending of each subMessage, converted to bytes in the following way:

- The first 4 bytes contain the message ID (4 UTF-8 characters)
- The next 3 bytes contain the sequenceNumber of the subMessage (3 UTF-8 characters)
- The next 1 byte contains the type of the subMessage (1 UTF-8 character, which can be character '1' or '2')
- All other bytes represent the data of the subMessage as is (so the first byte represents the header)

More precisely:

- If the channel is of type ClientChannel (belongs to BluetoothConnectionClient), the writeSubMessage method sends the message via the MESSAGE_RECEIVE_UUID characteristic (via the method seen above)
- If the channel is of type ServerChannel (belongs to BluetoothConnectionServer), the writeSubMessage method sends the message via the MESSAGE_SEND_UUID characteristic (via the method seen above), and the client responds confirming reception via the READ_RESPONSE_MESSAGE_RECEIVED_UUID characteristic

If something goes wrong, the channel immediately retries sending the message until it succeeds or we disconnect (this is why writeSubMessage runs on a separate thread, so we don't block the main thread if the message fails). The same happens if after 1 second we haven't received any confirmation of message reception.

Once all subMessages are sent, we're done.

**Note:** Based on the maximum value of sequenceNumber (16 million) and the maximum size of each subMessage (192 bytes), the maximum amount of data we can send with a single message (also for data sending, not just messages) is about 3GB. If you want to send a longer message than this, it will have to be split and reconstructed outside the bluetooth library by the program using it.

**Note:** Another limit of this library is that, since messageId is a 4-byte SequenceNumber, the maximum number of unique messages (complete messages, not sub messages) we can send to a single Peer during a connection session is 4 billion. Although it's practically impossible to reach this limit, I wanted to point it out (the same applies to data, but, having a separate id, the limit is independent of normal messages, and depends only on data type messages sent).

#### Message Reception:

Channels (both client and server type) have 2 arrays as internal parameters:

- **receivingMessages**: A list of BluetoothMessage type objects, containing all messages we are receiving but are not yet complete. Each message is composed of all subMessages of the same message we have received so far (each subMessage is added to the right message when we receive it) and its sequenceNumber is that of the last message we added to it
- **receivedMessages**: A list of BluetoothMessage type objects, containing all complete messages we have received (used to avoid the same message being received and notified multiple times)

When BluetoothConnectionClient (or Server) receives a subMessage via the respective characteristics (as we saw above), it first pauses any sending of subMessages for that channel (to increase stability), and also receives 2 fundamental data (via Android BLE APIs):

- A BluetoothDevice type object, representing the device from which we received the message, which will be used to find the channel with the corresponding Peer
- The subMessage data, which it uses, along with the Peer retrieved from the channel, to recreate a BluetoothMessage type object (which from now on we'll simply call subMessage)

First, it checks, via the id, if the subMessage belongs to receivedMessages. If so, it ignores it. If not, it checks if it belongs to receivingMessages:

- If it belongs to one of the messages in receivingMessages, then the subMessage data is added to the message, but only if the sequenceNumber is greater than the message's sequenceNumber (if not, it's simply not added and we continue)
- If it doesn't belong to any message in receivingMessage, then it's added as a new message in receivingMessages

Then it checks the type of the received subMessage:

- If it's final, then the (now complete) message is removed from receivingMessages, inserted into receivedMessages and BluetoothConnectionClient (or Server) is notified of the message reception (passing the message converted to the Message type seen above), which will notify the BluetoothCommunicator, which will notify the program using the library
- If it's not final, we simply continue

At this point, reception of the message is confirmed:

- If we're the client, it's done via the READ_RESPONSE_MESSAGE_RECEIVED characteristic, where we'll insert the ID and sequenceNumber of the received subMessage
- If we're the server, via a confirmation from Android BLE APIs (which will indicate to the client the confirmation of characteristic writing via onCharacteristicWrite, which will contain the value of the written characteristic, which will therefore contain the ID and sequenceNumber of the sent subMessage)

At this point, the other device (the one that sent the message) receives the response in BluetoothConnectionClient (or Server), where it checks the Peer that sent the confirmation (in the same way seen above), checks the pendingSubMessage of the channel to which the Peer belongs, and if the sequenceNumber and ID match (if they don't, the confirmation is ignored and nothing is done), it notifies the success of message sending to the channel, which will proceed with sending the next subMessage (and if subMessages are finished, with sending the next complete message).

**Note:** The sequenceNumber only serves to avoid receiving and adding an old subMessage. In fact, since for both client and server there's reception confirmation, and the next subMessage is sent only when we're certain that the previous one has arrived, it's not possible for a certain message to skip a subMessage. So the sequenceNumber we receive can only be 1 greater than the sequenceNumber of the last subMessage received for that message, or smaller, or equal. For this reason, subMessages received with a wrong sequenceNumber are simply ignored, because they are simply messages considered failed by the receiver (and resent) but received late.
</br></br>

### Disconnection Protocol Analysis

Disconnection characteristics:
- DISCONNECTION_SEND
- DISCONNECTION_RECEIVE

When the disconnect method of BluetoothCommunicator is called (passing the Peer we want to disconnect), the disconnect method is called (forwarding the Peer) on both BluetoothConnectionClient and BluetoothConnectionServer.
</br></br>
**Disconnection of a single Peer:**

The disconnect(Peer) method of BluetoothConnectionClient (or Server) searches for the Peer in the list of channels it contains. If it doesn't find it, it does nothing. If it finds the right channel, then (in a separate thread):

- If the Peer has isReconnecting = true, then the reconnection is cancelled
- If the Peer has isReconnecting = false, then the Peer's isDisconnecting is set to true, any message and data sending is interrupted, and the disconnection notification is sent via the DISCONNECTION_RECEIVE characteristic (or DISCONNECTION_SEND, if we're the server, with confirmation) (to which we send a single character, only because we can't send an empty message)
- At this point, the other device receives the notification, sends a confirmation response (automatically if it's a client) and calls the disconnect() method of the channel, the same we'll see below
- Now our device, once it receives the reception confirmation, or if 5 seconds have passed without a response, moves to the next step
- Now (still our device) the actual disconnection is performed by calling the channel's disconnect() method. This method, if not already done before, sets the Peer's isDisconnecting to true and interrupts any message and data sending. It also performs the actual disconnection:
  - If we're a client via the bluetoothGatt.disconnect() method of Android BLE APIs
  - If we're a server via the bluetoothGattServer.cancelConnection() method of Android BLE APIs

Now, both our device and the other, in BluetoothConnectionClient (or Server) will receive, from Android BLE APIs, via the channel, a disconnection notification (in onConnectionStateChange -> STATE_DISCONNECTED). At this point, via the callback's Peer, we retrieve the right channel, set the Peer's hardwareConnected parameter to false, remove the channel from the channel list contained in BluetoothConnectionClient (or Server) and notify the disconnection to BluetoothCommunicator, which will notify the program using the library.

In case of errors during disconnection, they are notified to the program using the library, without doing anything else.

**Disconnection of all Peers:**

In this case, the classic disconnection is simply performed but on the Peers of all channels, both client and server (in this case not one at a time, but all together (as we specified, they run on different threads)).

**Note:** This library, for disconnecting all Peers, also has a method to force disconnection by restarting bluetooth (in case of errors in normal disconnection), but we won't examine it because it's a function that no longer works with the latest Android APIs and will soon be removed and replaced with better methods for disconnection.
</br></br>

### Reconnection Protocol Analysis

CONNECTION_RESUMED_SEND

CONNECTION_RESUMED_RECEIVE

Let's see what happens in case of an unwanted disconnection:

Both client and server (in BluetoothConnectionClient/Server) receive the disconnection notification from the correct channel's callback (onConnectionStateChange -> STATE_DISCONNECTED), completely close the connection at the hardware level and set the following parameters of the Peer (saved in the respective channel) they lost connection with (received in the callback):

- isHardwareConnected: false
- isConnected: false
- isReconnecting: true

If not active, they start both advertising and discovery and notify the loss of connection to the program that is using the library. They also start a 30-second timer. If reconnection doesn't occur within this time, disconnection will start on that Peer. Message sending is also automatically suspended.

At this point, when the client device finds a Peer (in the same callback used for peer search that we saw at the beginning: in BluetoothCommunicator -> onScanResult), they compare its uniqueName with that of all Peers of all channels saved in BluetoothConnectionClient. If we find a channel corresponding to the Peer that has isReconnecting = true, then we update the Peer's BluetoothDevice with the one just found and, if its isRequestingReconnection parameter is false, we set it to true and send a classic connection request (as we described at the beginning, inserting the Peer in the pendingConnections queue).

**Note:** Any reconnection is only reinitialized by the client (ex-client to be precise) to avoid conflicts. This is why the server doesn't send any request even if it finds the right Peer. It will notify the program using the library as it does with any other found Peer.

From this point on, the connection process is identical to what we saw at the beginning, but instead of adding a channel to the channel list, both devices update the old channel's info with the new ones. Also, the connection request and acceptance/rejection process by the program using the library is skipped and everything happens automatically.

Once the connection process is finished, both devices set the parameters of the interested Peers to:

- isHardwareConnected: true
- isConnected: true
- isReconnecting: false
- isRequestingReconnection: false

Both devices notify, to the program that is using the library, that the connection has been reestablished and resume sending messages inserted up to this point in the queue (they can be added during the entire reconnection process) and the connection returns to normal.
</br></br></br>

## RTranslator Communication Protocol Analysis

So far we've seen how the communication protocol works for the bluetooth library. Now we'll see how communication works specifically for RTranslator's Conversation mode using the bluetooth library.

### Initial Connection:

Discovery and advertising always happen together (they start together and stop together). This combo is called **search**.

The rest of the connection happens in the classic way we seen above.

But when the connection finally succeeds, as the first thing, the client device sends its device ID (Settings.Secure.ANDROID_ID), via a normal message, with header "d".

When the server receives the id, it saves the client's Peer, with that ID as an identifier, among recent Peers (if it's already among recent Peers, it updates it) and responds by sending its ID to the client in the same way.

When the client receives the server's ID, it also saves the server's Peer among recent Peers (if it's already among recent Peers, it updates it), then sends its profile image as data (with header "i").

When the server receives the image, it saves it in the recent Peer info saved before and sends its profile image.

When the client receives the image, it also saves it in the recent Peer info saved before.

**Note:** After the connection, during the exchange of IDs and images, the two devices can exchange messages normally.

### Message Sending:

When a device finishes recognizing one or more sentences, it sends a message to all devices it's connected with. The message contains the transcription text and the device's language code (i.e., the transcription language).

Other devices, when they receive the message, extract the text and language and translate the text from the language indicated in the message to the device's language.

The operation of message sending has been seen [here](https://github.com/niedev/BluetoothCommunicatorDocs/blob/main/docs_gpt_claude.md#message-sending), the message structure is as follows:

The message is a Message type object, which we saw [here](https://github.com/niedev/BluetoothCommunicatorDocs/blob/main/docs_gpt_claude.md#message-sending), with the following attributes:

- **Sender**: A Peer type object containing information about the Peer that sent the message (this information is for received messages, so if we're sending a message this field can have null value)
- **Receiver**: A Peer type object that in our case has null value (to indicate to the BLE library that the message will be sent to all Peers connected to the current device)
- **Header**: A single character that can be used by the program to identify the message type. In this case it has value "m"
- **Data**: A byte array representing the actual content of the message. In this case it will contain the message text, followed by the message language code, followed by a character indicating the length of the language code (will be used, by taking the last character of the message, to extract the language code and the text to translate from the message)

### Reconnection:

This will be handled transparently by the library (which will simply notify when a Peer loses connection and when it recovers it).

### Disconnection:

Disconnection will be sent as a command to the library, which will handle all details and notify its success.
