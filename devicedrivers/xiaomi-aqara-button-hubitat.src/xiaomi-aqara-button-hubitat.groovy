/**
 *  Xiaomi Aqara Button - models WXKG11LM / WXKG12LM
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.6
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Based on SmartThings device handler code by a4refillpad
 *  Reworked and additional code for use with Hubitat Elevation hub by veeceeoh
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, guyeeba, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, veeceeoh, & xtianpaiva
 *
 *  Notes on capabilities of the different models:
 *  Model WXKG11LM (original revision)
 *    - Single click results in button 1 "pushed" event
 *    - Double-click results in button 2 "pushed" event
 *    - Triple-click results in button 3 "pushed" event
 *    - Quadruple-click results in button 4 "pushed" event
 *    - Button release is automatic, based on user-adjustable timer, results in button 0 "pushed" event
 *  Model WXKG11LM (new revision):
 *    - Single click results in button 1 "pushed" event
 *    - Hold for longer than 400ms results in button 1 "held" event
 *    - Release of button results in button 1 "released" event
 *    - Double click results in button 1 "doubleTapped" event
 *  Model WXKG12LM:
 *    - Single click results in button 1 "pushed" event
 *    - Hold for longer than 400ms results in button 1 "held" event
 *    - Release of button results in button 1 "released" event
 *    - Double click results in button 1 "doubleTapped" event
 *    - Shaking the button results in button 2 "pushed" event
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 *    However, the Aqara Button battery level can be retrieved immediately with a short-press of the reset button.
 *  + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *    Holding the sensor's reset button until the LED blinks will start pairing mode.
 *    3 quick flashes indicates success, while one long flash means pairing has not started yet.
 *    In either case, keep the sensor "awake" by short-pressing the reset button repeatedly, until recognized by Hubitat.
 *  + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, and follow
 *    the same steps for pairing. As long as it has not been removed from the Hubitat's device list, when the LED
 *    flashes 3 times, the sensor should be reconnected and will resume reporting as normal
 *
 */

metadata {
	definition (name: "Aqara Button", namespace: "veeceeoh", author: "veeceeoh") {
		capability "Battery"
		capability "DoubleTapableButton"
		capability "HoldableButton"
		capability "PushableButton"
		capability "ReleasableButton"
		capability "Sensor"

		attribute "lastCheckinEpoch", "String"
		attribute "lastCheckinTime", "String"
		attribute "batteryLastReplaced", "String"
		attribute "buttonPressedEpoch", "String"
		attribute "buttonPressedTime", "String"
		attribute "buttonHeldEpoch", "String"
		attribute "buttonHeldTime", "String"
		attribute "buttonReleasedEpoch", "String"
		attribute "buttonReleasedTime", "String"

		// Aqara Button - model WXKG11LM (original revision)
		fingerprint profileId: "0104", inClusters: "0000,FFFF,0006", outClusters: "0000,0004,FFFF", manufacturer: "LUMI", model: "lumi.sensor_switch.aq2", deviceJoinName: "Aqara Button WXKG11LM r1"
		// Aqara Button - model WXKG11LM (new revision)
		fingerprint profileId: "0104", inClusters: "0000,0012,0003", outClusters: "0000", manufacturer: "LUMI", model: "lumi.remote.b1acn01", deviceJoinName: "Aqara Button WXKG11LM r2"
		// Aqara Button - model WXKG12LM
		fingerprint profileId: "0104", inClusters: "0000,0012,0006,0001", outClusters: "0000", manufacturer: "LUMI", model: "lumi.sensor_switch.aq3", deviceJoinName: "Aqara Button WXKG12LM"

		command "resetBatteryReplacedDate"
	}

	preferences {
		//Button Config
		input "releaseTime", "number", title: "Model WXKG11LM - ORIGINAL revision ONLY: Delay after a single press to send 'release' (button 0 pushed) event", description: "Default = 2 seconds", range: "1..60"
		//Battery Voltage Range
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.9). Default = 2.9 Volts", description: "", type: "decimal", range: "2..2.9"
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.95 to 3.4). Default = 3.05 Volts", description: "", type: "decimal", range: "2.95..3.4"
		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	Map map = [:]

	// lastCheckinEpoch is for apps that can use Epoch time/date and lastCheckinTime can be used with Hubitat Dashboard
	sendEvent(name: "lastCheckinEpoch", value: now())
	sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())

	displayDebugLog("Parsing message: ${description}")

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0006") {
		// Model WXKG11LM (original revision) only
		map = parse11LMMessage(attrId, Integer.parseInt(valueHex))
	} else if (cluster == "0012") {
		if (device.data.model.startsWith("lumi.remote.b1acn01")) {
			// Model WXKG11LM (new revision) only
			map = parse11LMNewMessage(Integer.parseInt(valueHex[2..3],16))
		} else {
			// Model WXKG12LM only
			map = parse12LMMessage(Integer.parseInt(valueHex[2..3],16))
		}
	} else if (cluster == "0000" & attrId == "0005") {
		displayInfoLog("Reset button was short-pressed")
		// Parse battery level from longer type of announcement message
		map = (valueHex.size() > 60) ? parseBattery(valueHex.split('FF42')[1]) : [:]
	} else if (cluster == "0000" & (attrId == "FF01" || attrId == "FF02")) {
		// Parse battery level from hourly announcement message
		map = (valueHex.size() > 30) ? parseBattery(valueHex) : [:]
	} else if (!(cluster == "0000" & attrId == "0001")) {
		displayDebugLog("Unable to parse message")
	}

	if (map != [:]) {
		displayDebugLog("Creating event $map")
		return createEvent(map)
	} else
		return [:]
}

// Parse WXKG11LM (original revision) button message: press, double-click, triple-click, quad-click, and release
private parse11LMMessage(attrId, value){
	def result = [:]
	releaseTime = (releaseTime > 0) ? releaseTime : 2
	if ((attrId == "0000") && (value == 1001 || value == 1000)) {
		result = map11LMEvent(1)
		runIn(releaseTime, releaseButton)
	} else if (attrId=="8000") {
		result = map11LMEvent(value)
	}
	return result
}

// Build event map based on type of WXKG11LM (original revision) click
private Map map11LMEvent(value) {
	def messageType = ["released", "single-clicked", "double-clicked", "triple-clicked", "quadruple-clicked"]
	def timeStampType = (value == 0) ? "Released" : "Pressed"
	if (value <= 4) {
		displayInfoLog("Button was ${messageType[value]} (Button $value pushed)")
		updateDateTimeStamp(timeStampType)
		return [
			name: 'pushed',
			value: value,
			isStateChange: true,
			descriptionText: "Button was ${messageType[value]}"
		]
	} else {
		return [:]
	}
}

// Build event map based on type of WXKG11LM (new revision) action
private parse11LMNewMessage(value) {
	// Button message values (as integer): 0: hold, 1 = push, 2 = double-click, 255 = release
	def messageType = [0: "held", 1: "single-clicked", 2: "double-clicked", 255: "released"]
	def eventType = [0: "held", 1: "pushed", 2: "doubleTapped", 255: "released"]
	def timeStampType = [0: "Held", 1: "Pressed", 2: "Pressed", 255: "Released"]
	displayInfoLog("Button was ${messageType[value]} (Button 1 ${eventType[value]})")
	updateDateTimeStamp(timeStampType[value])
	return [
		name: eventType[value],
		value: 1,
		isStateChange: true,
		descriptionText: "Button was ${messageType[value]}"
	]
}

// Build event map based on type of WXKG12LM action
private parse12LMMessage(value) {
	// Button message values (as integer): 1 = push, 2 = double-click, 16 = hold, 17 = release, 18 = shake
	def messageType = [1: "single-clicked", 2: "double-clicked", 16: "held", 17: "released", 18: "shaken"]
	def eventType = [1: "pushed", 2: "doubleTapped", 16: "held", 17: "released", 18: "pushed"]
	def timeStampType = [1: "Pressed", 2: "Pressed", 16: "Held", 17: "Released", 18: "Pressed"]
	def buttonNum = (value == 18) ? 2 : 1
	displayInfoLog("Button was ${messageType[value]} (Button ${buttonNum} ${eventType[value]})")
	updateDateTimeStamp(timeStampType[value])
	return [
		name: eventType[value],
		value: buttonNum,
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

def releaseButton() {
	def result = [:]
	displayDebugLog("Calling button release after delay of ${releaseTime = releaseTime ? releaseTime : 2} seconds")
	result = map11LMEvent(0)
	sendEvent(result)
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
	def nButtons = 2
	def modelText = "WXKG12LM"
	def zigbeeModel = device.data.model
	displayInfoLog("Reported ZigBee model ID is $zigbeeModel")
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	if (zigbeeModel.startsWith("lumi.sensor_switch.aq2")) {
		modelText = "WXKG11LM (original revision)."
		nButtons = 4
	} else if (zigbeeModel.startsWith("lumi.remote.b1acn01")) {
		modelText = "WXKG11LM (new revision)."
		nButtons = 1
	}
	displayInfoLog("Reported device model is $modelText.")
	if (!state.numButtons) {
		sendEvent(name: "numberOfButtons", value: nButtons)
		displayInfoLog("Number of buttons set to $nButtons")
		state.numButtons = nButtons
	}
}
