/*
 *  Xiaomi Aqara Wireless Smart Light Switch
 *  2016 & 2018 revisions of models WXKG03LM (1 button) and WXKG02LM (2 buttons)
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.6b
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Based on SmartThings device handler code by a4refillpad
 *  Reworked for use with Hubitat Elevation hub by gn0st1c with additional code by veeceeoh
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, mike.maxwell, rinkek, ronvandegraaf, snalee, tmleafs, twonk, veeceeoh, & xtianpaiva
 *
 *  Notes on capabilities of the different models:
 *  Model WXKG03LM (1 button) - 2016 Revision:
 *    - Single press results in button 1 "pushed" event
 *  Model WXKG03LM (1 button) - 2018 Revision:
 *    - Single press results in button 1 "pushed" event
 *    - Double click results in button 1 "doubleTapped" event
 *    - Hold for longer than 400ms results in button 1 "held" event
 *  Model WXKG02LM (2 button) - 2016 Revision:
 *    - Single press of left button results in button 1 "pushed" event
 *    - Single press of right button results in button 2 "pushed" event
 *    - Single press of both buttons results in button 3 "pushed" event
 *  Model WXKG02LM (2 button) - 2018 Revision:
 *    - Single press of left button results in button 1 "pushed" event
 *    - Single press of right button results in button 2 "pushed" event
 *    - Single press of both buttons results in button 3 "pushed" event
 *    - Double click of left button results in button 1 "doubleTapped" event
 *    - Double click of right button results in button 2 "doubleTapped" event
 *    - Double click of both buttons results in button 3 "doubleTapped" event
 *    - Hold of left button for longer than 400ms results in button 1 "held" event
 *    - Hold of right button for longer than 400ms results in button 2 "held" event
 *    - Hold of both buttons for longer than 400ms results in button 3 "held" event
 *
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *
 *  https://xiaomi-mi.com/sockets-and-sensors/xiaomi-aqara-smart-light-control-set
 */

metadata {
	definition (name: "Xiaomi Aqara Wireless Smart Light Switch", namespace: "veeceeoh", author: "gn0st1c") {
		capability "Battery"
		capability "DoubleTapableButton"
		capability "HoldableButton"
		capability "PushableButton"
		capability "Sensor"

		attribute "lastCheckinEpoch", "String"
		attribute "lastCheckinTime", "String"
		attribute "batteryLastReplaced", "String"
		attribute "buttonPressedEpoch", "String"
		attribute "buttonPressedTime", "String"
		attribute "buttonDoubleTappedEpoch", "String"
		attribute "buttonDoubleTappedTime", "String"
		attribute "buttonHeldEpoch", "String"
		attribute "buttonHeldTime", "String"

		// Aqara Wireless Smart Light Switch - one button - model WXKG03LM - 2016 Revision
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw1lu"
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw1"
		// Aqara Wireless Smart Light Switch - two button - model WXKG02LM - 2016 Revision
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw2Un"
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw2"
		// Aqara Wireless Smart Light Switch - one button - model WXKG03LM - 2018 Revision
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.remote.b186acn01"
		// Aqara Wireless Smart Light Switch - two button - model WXKG02LM - 2018 Revision
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.remote.b286acn01"

		command "resetBatteryReplacedDate"
	}

	preferences {
		//Battery Voltage Range
 		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.9). Default = 2.9 Volts", description: "", type: "decimal", range: "2..2.9"
 		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.95 to 3.4). Default = 3.05 Volts", description: "", type: "decimal", range: "2.95..3.4"
 		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
		//Firmware 2.0.5 Compatibility Fix Config
		input name: "oldFirmware", type: "bool", title: "DISABLE 2.0.5 firmware compatibility fix (for users of 2.0.4 or earlier)", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def endpoint = description.split(",").find {it.split(":")[0].trim() == "endpoint"}?.split(":")[1].trim()
	def cluster	= description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	Map map = [:]

	if (!oldFirmware & valueHex != null)
		// Reverse order of bytes in description's value hex string - required for Hubitat firmware 2.0.5 or newer
		valueHex = reverseHexString(valueHex)

	displayDebugLog("Parsing message: ${description}")
	displayDebugLog("Message payload: ${valueHex}")

	// lastCheckinEpoch is for apps that can use Epoch time/date and lastCheckinTime can be used with Hubitat Dashboard
	sendEvent(name: "lastCheckinEpoch", value: now())
	sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0006") {
		// Parse Model WXKG02LM button press: endpoint 01 = left, 02 = right, 03 = both
		map = parse02LMMessage(Integer.parseInt(endpoint))
	} else if (cluster == "0012") {
		// Parse Model WXKG03LM button message: value 0 = held, 1 = pushed, 2 = double-tapped
		map = parse03LMMessage(Integer.parseInt(valueHex[2..3],16))
	} else if (cluster == "0000" & attrId == "0005") {
		displayDebugLog "Reset button was short-pressed"
		// Parse battery level from longer type of announcement message
		map = (valueHex.size() > 60) ? parseBattery(valueHex.split('FF42')[1]) : [:]
	} else if (cluster == "0000" & (attrId == "FF01" || attrId == "FF02")) {
		// Parse battery level from hourly announcement message
		map = (valueHex.size() > 30) ? parseBattery(valueHex) : [:]
	} else if (!(cluster == "0000" & attrId == "0001")) {
		displayDebugLog "Unable to parse ${description}"
	}

	if (map != [:]) {
		displayDebugLog("Creating event $map")
		return createEvent(map)
	} else
		return [:]
}

// Reverses order of bytes in hex string
def reverseHexString(hexString) {
	def reversed = ""
	for (int i = hexString.length(); i > 0; i -= 2) {
		reversed += hexString.substring(i - 2, i )
	}
	return reversed
}

// Build event map based on type of WXKG02LM button press
private Map parse02LMMessage(value) {
	def pushType = ["", "Left", "Right", "Both"]
	def descText = "${pushType[value]} button${(value == 3) ? "s" : ""} pressed (Button $value pushed)"
	displayInfoLog(descText)
	updateDateTimeStamp("Pressed")
	return [
		name: 'pushed',
		value: value,
		isStateChange: true,
		descriptionText: descText
	]
}

// Build event map based on type of WXKG03LM button press
private parse03LMMessage(value) {
	// Button message values (as integer): value 0 = held, 1 = pushed, 2 = double-tapped
	def messageType = ["held", "pressed", "double-tapped"]
	def eventType = ["held", "pushed", "doubleTapped"]
	def timeStampType = ["Held", "Pressed", "DoubleTapped"]
	displayInfoLog("Button was ${messageType[value]}")
	updateDateTimeStamp(timeStampType[value])
	return [
		name: eventType[value],
		value: 1,
		isStateChange: true,
		descriptionText: "Button was ${messageType[value]}"
	]
}

// Generate buttonPressedEpoch/Time, buttonHeldEpoch/Time, or buttonReleasedEpoch/Time event for Epoch time/date app or Hubitat dashboard use
def updateDateTimeStamp(timeStampType) {
	displayDebugLog("Setting button${timeStampType}Epoch and button${timeStampType}Time to current date/time")
	sendEvent(name: "button${timeStampType}Epoch", value: now(), descriptionText: "Updated button${timeStampType}Epoch")
	sendEvent(name: "button${timeStampType}Time", value: new Date().toLocaleString(), descriptionText: "Updated button${timeStampType}Time")
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(description) {
	displayDebugLog("Battery parse string = ${description}")
	def MsgLength = description.size()
	def rawValue
	for (int i = 4; i < (MsgLength-3); i+=2) {
		if (description[i..(i+1)] == "21") { // Search for byte preceeding battery voltage bytes
			rawValue = Integer.parseInt((description[(i+4)..(i+5)] + description[(i+2)..(i+3)]),16)
			break
		}
	}
	def rawVolts = rawValue / 1000
	def minVolts = voltsmin ? voltsmin : 2.9
	def maxVolts = voltsmax ? voltsmax : 3.05
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	def descText = "Battery level is ${roundedPct}% (${rawVolts} Volts)"
	displayInfoLog(descText)
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		isStateChange: true,
		descriptionText: descText
	]
	return result
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: new Date())
	displayInfoLog("Setting Battery Last Replaced to current date${newlyPaired}")
}

// this call is here to avoid Groovy errors when the Push command is used
// it is empty because the Xioami button is non-controllable
def push() {
	displayDebugLog("No action taken on Push Command. This button cannot be controlled.")
}

// installed() runs just after a sensor is paired
def installed() {
	state.prefsSetCount = 0
	displayInfoLog("Installing")
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
	displayInfoLog("Configuring")
	init()
	state.prefsSetCount = 1
	return
}

// updated() runs every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	init()
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def init() {
	def nButtons = 0
	def revYear = "16"
	def zigbeeModel = device.data.model ? device.data.model : "unknown"
	displayInfoLog("Reported ZigBee model ID is $zigbeeModel")
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	if (zigbeeModel == "lumi.sensor_ht")
		log.warn "Model RTCGQ01LM Xiaomi Temperature Humidity Sensor Detected. Please manually assign Xiaomi Temperature Humidity Sensor device driver"
	else if (zigbeeModel.startsWith("lumi.sensor_8"))
		nButtons = Integer.parseInt(zigbeeModel[16],16)
	else if (zigbeeModel.startsWith("lumi.remote")) {
		nButtons = Integer.parseInt(zigbeeModel[13],16)
		revYear = "18"
	}
	else
		log.warn "Reported device model is unknown"
	if (nButtons != 0) {
		def modelText = (nButtons == 1) ? "03" : "02"
		displayInfoLog("Reported model is WXKG${modelText}LM - 20$revYear revision ($nButtons button Aqara Wireless Smart Light Switch)")
	}
	if (!state.numOfButtons) {
		sendEvent(name: "numberOfButtons", value: nButtons)
		displayInfoLog("Number of buttons set to $nButtons")
		state.numOfButtons = nButtons
	}
}
