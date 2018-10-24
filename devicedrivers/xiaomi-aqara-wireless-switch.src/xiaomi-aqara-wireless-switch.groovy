/*
 *  Xiaomi Aqara Wireless Smart Light Switch
 *  Models WXKG03LM (1 button) and WXKG02LM (2 buttons)
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.5b
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
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, veeceeoh, & xtianpaiva
 *
 *  Notes on capabilities of the different models:
 *  Model WXKG03LM (1 button):
 *    - Single press results in button 1 "pushed" event
 *    - Double click results in button 1 "doubleTapped" event
 *    - Single click results in button 1 "held" event
 *  Model WXKG02LM (2 button):
 *    - Single click results in button 1 "pushed" event
 *    - Hold for longer than 400ms results in button 1 "held" event
 *
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *
 *  https://xiaomi-mi.com/sockets-and-sensors/xiaomi-aqara-smart-light-control-set
 */

metadata {
	definition (name: "Xiaomi Aqara Dual Button Light Switch", namespace: "veeceeoh", author: "gn0st1c") {
		capability "Battery"
		capability "DoubleTapableButton"
		capability "HoldableButton"
		capability "PushableButton"
		capability "Sensor"

		attribute "lastCheckin", "String"
		attribute "lastCheckinTime", "String"
		attribute "batteryLastReplaced", "String"
		attribute "buttonPressed", "String"
		attribute "buttonPressedTime", "String"
		attribute "buttonDoubleTapped", "String"
		attribute "buttonDoubleTappedTime", "String"
		attribute "buttonHeld", "String"
		attribute "buttonHeldTime", "String"

		// Aqara Wireless Smart Light Switch - one button - model WXKG03LM
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw1lu"
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw1"
		// Aqara Wireless Smart Light Switch - two button - model WXKG02LM
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw2Un"
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw2"

		command "resetBatteryReplacedDate"
	}

	preferences {
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
	def endpoint = description.split(",").find {it.split(":")[0].trim() == "endpoint"}?.split(":")[1].trim()
	def cluster	= description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	Map map = [:]

	// lastCheckin can be used with webCoRE
	sendEvent(name: "lastCheckin", value: now())
	sendEvent(name: "lastCheckinTime", value: formatDate()

	displayDebugLog("Parsing message: ${description}")

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0006") {
		// Parse Model WXKG02LM button press: endpoint 01 = left, 02 = right, 03 = both
		map = parse02LMMessage(Integer.parseInt(endpoint))
	} else if (cluster == "0012") {
		// Parse Model WXKG03LM button message: value 0 = push, 1 = double-click, 2 = hold
		map = parse03LMMessage(Integer.parseInt(valueHex[2..3],16))
		}
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

// Build event map based on type of WXKG02LM button press
private Map parse02LMMessage(value) {
	def pushType = ["", "Left", "Right", "Both"]
	def descText = "${pushType[value]} button${(value == 3) ? "s" : ""} pressed (Button $value pushed)"
	displayInfoLog(descText)
	updateCoREEvent(coreType[value])
	if (!(state.numOfButtons == 3))
		init()
	return [
		name: 'pushed',
		value: value,
		isStateChange: true,
		descriptionText: descText
	]
}

// Build event map based on type of WXKG03LM button press
private parse03LMMessage(value) {
	// Button message values (as integer): 0 = push, 1 = double-click, 2 = hold
	def messageType = ["pressed", "double-tapped", "held"]
	def eventType = ["pushed", "doubleTapped", "held"]
	def coreType = ["Pressed", "DoubleTapped", "Held"]
	if (!(state.numOfButtons == 1)) {
		sendEvent(name: "numberOfButtons", value: 1)
		displayInfoLog("Number of buttons set to 1 for model WXKG03LM")
		state.numOfButtons = 1
	}
	displayInfoLog("Button was ${messageType[value]}")
	updateCoREEvent(coreType[value])
	return [
		name: eventType[value],
		value: 1,
		isStateChange: true,
		descriptionText: "Button was ${messageType[value]}"
	]
}

// Generate buttonPressed(Time), buttonHeld(Time), or buttonReleased(Time) event for webCoRE/dashboard use
def updateCoREEvent(coreType) {
	displayDebugLog("Setting button${coreType} & button${coreType}Time to current date/time for webCoRE/dashboard use")
	sendEvent(name: "button${coreType}", value: now(), descriptionText: "Updated button${coreType} (webCoRE)")
	sendEvent(name: "button${coreType}Time", value: formatDate(), descriptionText: "Updated button${coreType}Time")
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
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	if (!state.numOfButtons) {
		sendEvent(name: "numberOfButtons", value: 3)
		displayInfoLog("Number of buttons set to 3 (on first button press of model WXKG03LM this will be changed to 1).")
		state.numOfButtons = 3
	}
}
