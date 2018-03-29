/**
 *  Xiaomi Aqara Button - models WXKG11LM / WXKG12LM
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.5
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
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, veeceeoh, & xtianpaiva
 *  Reworked and additional code for use with Hubitat Elevation hub by veeceeoh
 *
 *  Notes on capabilities of the different models:
 *  Models WXKG11LM
 *    - Only single press is supported, sent as button 1 "pushed" event
 *  Model WXKG11LM:
 *    - Single click results in button 1 "pushed" event
 *    - Hold for longer than 400ms results in button 1 "held" event
 *    - Double click results in button 2 "pushed" event
 *    - Shaking the button results in button 3 "pushed" event
 *    - Single or double click results in custom "lastPressedCoRE" event for webCoRE use
 *    - Release of button results in "lastReleasedCoRE" event for webCoRE use
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
		capability "PushableButton"
		capability "HoldableButton"
		capability "Sensor"
		capability "Battery"

		attribute "lastCheckin", "String"
		attribute "batteryLastReplaced", "String"
		attribute "buttonPressed", "String"
		attribute "buttonHeld", "String"
		attribute "buttonReleased", "String"

		// Aqara Button - original revision - model WXKG11LM
		fingerprint endpointId: "01", profileId: "0104", deviceId: "5F01", inClusters: "0000,FFFF,0006", outClusters: "0000,0004,FFFF", manufacturer: "LUMI", model: "lumi.sensor_switch.aq2"
		// Aqara Button - new revision - model WXKG12LM
		fingerprint endpointId: "01", profileId: "0104", inClusters: "0000,0012,0006,0001", outClusters: "0000", manufacturer: "LUMI", model: "lumi.sensor_switch.aq3"

		command "resetBatteryReplacedDate"
	}

	preferences {
		//Button Config
		input "releaseTime", "number", title: "MODEL WXKG11LM ONLY: Delay after a single press to send 'release' (button 0 pushed) event", description: "Default = 2.0 seconds", range: "1..60"
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

	// lastCheckin can be used with webCoRE
	sendEvent(name: "lastCheckin", value: now())

	displayDebugLog("Parsing message: ${description}")

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0006") {
		// Model WXKG11LM only
		map = parse11LMMessage(attrId, Integer.parseInt(valueHex))
	} else if (cluster == "0012") {
		// Model WXKG12LM only
		map = parse12LMMessage(Integer.parseInt(valueHex[2..3],16))
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

// Parse WXKG11LM button message (press, double-click, triple-click, quad-click, and release)
private parse11LMMessage(attrId, value){
	def result = [:]
	releaseTime = releaseTime ? releaseTime : 2

	if ((attrId == "0000") && (value == 1001 || value == 1000)) {
		result = map11LMEvent(1)
		runIn(releaseTime, releaseButton)
	} else if (attrId=="8000") {
		result = map11LMEvent(value)
	}
	return result
}

// Build event map based on type of WXKG11LM click
private Map map11LMEvent(value) {
	def clickType = ["released", "single-clicked", "double-clicked", "triple-clicked", "quadruple-clicked"]
	def coreType = (value == 0) ? "Released" : "Pressed"
	if (value <= 4) {
		displayInfoLog("Button was ${clickType[value]} (Button $value pushed)")
		updateCoREEvent(coreType)
		return [
			name: 'pushed',
			value: value,
			isStateChange: true,
			descriptionText: "Button was ${clickType[value]}"
		]
	} else {
		return [:]
	}
}

// Build event map based on type of WXKG12LM action
private parse12LMMessage(value) {
	// Button message values (as integer): 1 = push, 2 = double-click, 16 = hold, 17 = release, 18 = shake
	value = (value < 3) ? value : (value - 13)
	def messageType = ["", "single-clicked", "double-clicked", "held", "released", "shaken"]
	def eventType = (value == 3) ? "held" : "pushed"
	def coreType = ["", "Pressed", "Pressed", "Held", "Released", "Pressed"]
	def buttonNum = [1, 1, 2, 1, 0, 3]
	displayInfoLog("Button was ${messageType[value]} (Button ${buttonNum[value]} $eventType)")
	updateCoREEvent(coreType[value])
	return [
		name: eventType,
		value: buttonNum[value],
		isStateChange: true,
		descriptionText: "Button was ${messageType[value]}"
	]
}

// Generate buttonPressed, buttonHeld, or buttonReleased event for webCoRE use
def updateCoREEvent(coreType) {
	displayDebugLog("Setting button${coreType} to current date/time for webCoRE")
	sendEvent(name: "button${coreType}", value: now(), descriptionText: "Updated button${coreType} (webCoRE)")
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
	sendEvent(name: "numberOfButtons", value: 4)
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
	displayInfoLog("Configuring")
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	sendEvent(name: "numberOfButtons", value: 4)
	displayInfoLog("Number of buttons = 4 (model WXKG12LM only uses buttons 1-3)")
	state.prefsSetCount = 1
	return
}

// updated() runs every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	sendEvent(name: "numberOfButtons", value: 4)
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}
