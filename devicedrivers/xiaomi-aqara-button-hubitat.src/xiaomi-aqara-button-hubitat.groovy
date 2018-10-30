/**
 *  Xiaomi Aqara Button - models WXKG11LM / WXKG12LM
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.55b
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
	definition (name: "Xiaomi Aqara Button", namespace: "veeceeoh", author: "bspranger") {
		capability "Battery"
		capability "DoubleTapableButton"
		capability "HoldableButton"
		capability "PushableButton"
		capability "ReleasableButton"
		capability "Sensor"

		attribute "lastCheckin", "String"
		attribute "lastCheckinTime", "String"
		attribute "batteryLastReplaced", "String"
		attribute "buttonPressed", "String"
		attribute "buttonPressedTime", "String"
		attribute "buttonHeld", "String"
		attribute "buttonHeldTime", "String"
		attribute "buttonReleased", "String"
		attribute "buttonReleasedTime", "String"

		// Aqara Button - model WXKG11LM (original revision)
		fingerprint endpointId: "01", profileId: "0104", deviceId: "5F01", inClusters: "0000,FFFF,0006", outClusters: "0000,0004,FFFF", manufacturer: "LUMI", model: "lumi.sensor_switch.aq2"
		// Aqara Button - model WXKG11LM (new revision)
		fingerprint endpointId: "01", profileId: "0104", deviceId: "5F01", inClusters: "0000,0012,0003", outClusters: "0000", manufacturer: "LUMI", model: "lumi.remote.b1acn01"
		// Aqara Button - model WXKG12LM
		fingerprint endpointId: "01", profileId: "0104", inClusters: "0000,0012,0006,0001", outClusters: "0000", manufacturer: "LUMI", model: "lumi.sensor_switch.aq3"

		command "resetBatteryReplacedDate"
	}

	preferences {
		//Button Config
		input "releaseTime", "number", title: "MODEL WXKG11LM ONLY: Delay after a single press to send 'release' (button 0 pushed) event", description: "Default = 2 seconds", range: "1..60"
		//Battery Voltage Range
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.7)", description: "Default = 2.5 Volts", type: "decimal", range: "2..2.7"
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.8 to 3.4)", description: "Default = 3.0 Volts", type: "decimal", range: "2.8..3.4"
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

	// lastCheckin(Time) can be used with webCoRE/Hubitat dashboard
	sendEvent(name: "lastCheckin", value: now())
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
	displayInfoLog("Button was ${messageType[value]} (Button ${buttonNum} $eventType[value])")
	updateDateTimeStamp(timeStampType[value])
	return [
		name: eventType[value],
		value: buttonNum,
		isStateChange: true,
		descriptionText: "Button was ${messageType[value]}"
	]
}

// Generate buttonPressed(Time), buttonHeld(Time), or buttonReleased(Time) event for webCoRE/Hubitat dashboard use
def updateDateTimeStamp(timeStampType) {
	displayDebugLog("Setting button${timeStampType} to current date/time for webCoRE")
	sendEvent(name: "button${timeStampType}", value: now(), descriptionText: "Updated button${timeStampType} (webCoRE)")
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
	def minVolts = voltsmin ? voltsmin : 2.5
	def maxVolts = voltsmax ? voltsmax : 3.0
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

// this call is here to avoid Groovy errors when the Hold command is used
// it is empty because the Xioami button is non-controllable
def hold() {
	displayDebugLog("No action taken on Hold Command. This button cannot be controlled!")
}

// installed() runs just after a sensor is paired
def installed() {
	state.prefsSetCount = 0
	displayInfoLog("Installing")
	setNumButtons()
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
	displayInfoLog("Configuring")
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	setNumButtons()
	state.prefsSetCount = 1
	return
}

// updated() runs every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	setNumButtons()
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def setNumButtons() {
	if (!state.numButtons) {
		if (device.data.model.startsWith("lumi.sensor_switch.aq2")) {
			displayInfoLog("Model is WXKG11LM (original revision). Number of buttons set to 4.")
			state.numButtons = 4
		} else if (device.data.model.startsWith("lumi.remote.b1acn01")) {
			displayInfoLog("Model is WXKG11LM (new revision). Number of buttons set to 1.")
			state.numButtons = 4
		} else {
			displayInfoLog("Model is WXKG12LM. Number of buttons set to 2.")
			state.numButtons = 2
		}
		sendEvent(name: "numberOfButtons", value: state.numButtons)
	}
}
