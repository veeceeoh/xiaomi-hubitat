/**
 *  Xiaomi "Original" Button
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.7b
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
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *  Reworked and additional code for use with Hubitat Elevation hub by veeceeoh
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 *    However, the Aqara Door/Window sensor battery level can be retrieved immediately with a short-press of the reset button.
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
	definition (name: "Xiaomi Button", namespace: "veeceeoh", author: "veeceeoh") {
		capability "PushableButton"
		capability "HoldableButton"
		capability "Sensor"
		capability "Battery"

		attribute "lastCheckin", "String"
		attribute "lastCheckinDate", "String"
		attribute "batteryLastReplaced", "String"
		attribute "buttonReleased", "String"

		// this fingerprint is identical to the one for Xiaomi "Original" Door/Window Sensor except for model name
		fingerprint endpointId: "01", profileId: "0104", deviceId: "0104", inClusters: "0000,0003,FFFF,0019", outClusters: "0000,0004,0003,0006,0008,0005,0019", manufacturer: "LUMI", model: "lumi.sensor_switch"

		command "resetBatteryReplacedDate"
	}

	preferences {
		//Button Config
		input "waittoHeld", "number", title: "Hold button for __ seconds to set button 1 'held' state (default = 1).", description: "", range: "1..60"
		//Date & Time Config
		input name: "dateformat", type: "enum", title: "Date Format for lastCheckin: US (MDY), UK (DMY), or Other (YMD)", description: "", options:["US","UK","Other"]
		input name: "clockformat", type: "bool", title: "Use 24 hour clock", description: ""
		//Battery Reset Config
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.7)", type: "decimal", range: "2..2.7", defaultValue: 2.5
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.8 to 3.4)", type: "decimal", range: "2.8..3.4", defaultValue: 3
		//Debug logging Config
		input name: "debugLogging", type: "bool", title: "Display debug log messages", description: "", defaultValue: false
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	displayDebugLog("Parsing description: ${description}")

	// Determine current time and date in the user-selected date format and clock style
	def now = formatDate()
	def nowDate = new Date(now).getTime()

	// lastCheckin and lastPressedDate can be used to determine if the sensor is "awake" and connected
	sendEvent(name: "lastCheckin", value: now)
	sendEvent(name: "lastCheckinDate", value: nowDate)

	Map map = [:]

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0006") {
		map = parseButtonMessage(Integer.parseInt(valueHex))
	} else if (cluster == "0000" & attrId == "0005") {
		displayDebugLog("Reset button was short-pressed")
		map = (valueHex.size() > 60) ? parseBattery(valueHex.split('FF42')[1]) : [:]
	} else if (cluster == "0000" & (attrId == "FF01" || attrId == "FF02")) {
		map = (valueHex.size() > 30) ? parseBattery(valueHex) : [:]
	} else if (!(cluster == "0000" & attrId == "0001")) {
		displayDebugLog("Unable to parse ${description}")
	}

	if (map) {
		displayDebugLog(map.descriptionText)
		return createEvent(map)
	} else
		return [:]
}

// Parse button message (press, double-click, triple-click, quad-click, and release)
private parseButtonMessage(attrValue) {
	def clickType = ["", "single", "double", "triple", "quadruple", "shizzle"]
	attrValue = (attrValue < 5) ? attrValue : 5
	displayDebugLog("Attribute value = ${attrValue}, Click type = ${clickType}")
	// On any release generate buttonReleased event for webCoRE use
	if (attrValue == 1) {
		sendEvent(name: "buttonReleased", value: formatDate(), descriptionText: "Button was released")
		displayDebugLog("Button was released")
	}
	// On single-press start heldState countdown but do not generate event
	if (attrValue == 0) {
		runIn((waittoHeld ?: 1), heldState)
		state.countdownActive = true
	// On multi-click or release when countdown active generate a pushed event
	} else if (state.countdownActive == true || attrValue > 1) {
		state.countdownActive = false
		return [
			name: 'pushed',
			value: attrValue,
			isStateChange: true,
			descriptionText: "Button ${attrValue} was pushed (${clickType[attrValue]}-click)"
		]
	}
	return [:]
}

//set held state if button has not yet been released after single-press
def heldState() {
	def descText = "Button 1 was held"
	if (state.countdownActive == true) {
		state.countdownActive = false
		sendEvent(
			name: 'held',
			value: 1,
			isStateChange: true,
			descriptionText: descText
		)
	displayDebugLog(descText)
	}
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
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		isStateChange: true,
		descriptionText: "Battery level is ${roundedPct}%, raw battery is ${rawVolts}V"
	]
	return result
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def now = formatDate(true)
	def logText = "Setting Battery Last Replaced to current date"
	sendEvent(name: "batteryLastReplaced", value: now)
	if (paired)
		log.debug "${logText} for newly paired sensor"
	displayDebugLog(logText)
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

// this call is here to avoid Groovy errors when the Push command is used
// it is empty because the Xioami button is non-controllable
def push() {
	displayDebugLog("Fo' shizzle this button can't be controlled!")
}

// this call is here to avoid Groovy errors when the Hold command is used
// it is empty because the Xioami button is non-controllable
def hold() {
	displayDebugLog("Fo' shizzle this button can't be controlled!")
}

// installed() runs just after a sensor is paired
def installed() {
	log.debug "${device.displayName}: Installing"
	sendEvent(name: "numberOfButtons", value: 5)
	state.countdownActive = false
	if (!batteryLastReplaced)
		resetBatteryReplacedDate(true)
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
	log.debug "${device.displayName}: Configuring"
	sendEvent(name: "numberOfButtons", value: 5)
	log.debug "${device.displayName}: Number of buttons = 5"
	state.countdownActive = false
	return
}

// updated() runs every time user saves preferences
def updated() {
	displayDebugLog("Updating preference settings")
	sendEvent(name: "numberOfButtons", value: 5)
	state.countdownActive = false
}

def formatDate(batteryReset) {
	def correctedTimezone = ""
	def timeString = clockformat ? "HH:mm:ss" : "h:mm:ss aa"

	// If user's hub timezone is not set, display error messages in log and events log, and set timezone to GMT to avoid errors
	if (!(location.timeZone)) {
		correctedTimezone = TimeZone.getTimeZone("GMT")
		log.error "${device.displayName}: Time Zone not set, so GMT was used. Please set up your Hubitat hub location."
		sendEvent(name: "error", value: "", descriptionText: "ERROR: Time Zone not set, so GMT was used. Please set up your Hubitat hub location.")
	}
	else {
		correctedTimezone = location.timeZone
	}

	if (dateformat == "US" || dateformat == "" || dateformat == null) {
		if (batteryReset)
			return new Date().format("MMM dd yyyy", correctedTimezone)
		else
			return new Date().format("EEE MMM dd yyyy ${timeString}", correctedTimezone)
	}
	else if (dateformat == "UK") {
		if (batteryReset)
			return new Date().format("dd MMM yyyy", correctedTimezone)
		else
			return new Date().format("EEE dd MMM yyyy ${timeString}", correctedTimezone)
	}
	else {
		if (batteryReset)
			return new Date().format("yyyy MMM dd", correctedTimezone)
		else
			return new Date().format("EEE yyyy MMM dd ${timeString}", correctedTimezone)
	}
}
