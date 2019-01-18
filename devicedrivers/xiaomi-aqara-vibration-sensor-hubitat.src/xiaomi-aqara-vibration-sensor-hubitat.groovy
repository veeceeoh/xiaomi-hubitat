/**
 *  Xiaomi Aqara Vibration Sensor
 *  Model DJT11LM
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
 *  Reworked for use with Hubitat Elevation hub by veeceeoh
 *  Additional contributions to code by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, oltman, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
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
 *    flashes 3 times, the Aqara Motion Sensor should be reconnected and will resume reporting as normal
 */

metadata {
	definition (name: "Xiaomi Aqara Vibration Sensor", namespace: "veeceeoh", author: "veeceeoh") {
		capability "Acceleration Sensor"
		capability "Battery"
		capability "Configuration"
		capability "Contact Sensor"
		capability "Motion Sensor"
		capability "PushableButton"
		capability "Refresh"
		capability "Sensor"

		attribute "activityLevel", "String"
		attribute "batteryLastReplaced", "String"
		attribute "lastCheckinEpoch", "String"
		attribute "lastCheckinTime", "Date"
		attribute "lastDropEpoch", "String"
		attribute "lastDropTime", "Date"
		attribute "lastStationaryEpoch", "String"
		attribute "lastStationaryTime", "Date"
		attribute "lastTiltEpoch", "String"
		attribute "lastTiltTime", "Date"
		attribute "lastVibrationEpoch", "String"
		attribute "lastVibrationTime", "Date"
		attribute "sensitivityLevel", "String"
		attribute "sensorStatus", "enum", ["vibrating", "tilted", "dropped", "Stationary"]
		attribute "tiltAngle", "String"

		fingerprint profileId: "0104", deviceId: "000A", inClusters: "0000,0003,0019,0101", outClusters: "0000,0004,0003,0005,0019,0101", manufacturer: "LUMI", model: "lumi.vibration.aq1"

		command "resetBatteryReplacedDate"
		command "setOpenPosition"
		command "setClosedPosition"
	}

	preferences {
		//Reset to No Motion Config
		input "motionreset", "number", title: "After vibration/movement is detected, wait ___ second(s) until resetting 'motion active' to 'inactive'. (default = 65)", description: "", range: "1..7200"
		//3-Axis Angle Open/Close Position Range Margin of Error Config
		input name: "marginOfError", title: "Margin of error to use comparing sensor position to user-set open/close positions (default = 10.0)", description: "", type: "decimal", range: "0..100"
		//Battery Voltage Range
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.9). Default = 2.9 Volts", description: "", type: "decimal", range: "2..2.9"
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.95 to 3.4). Default = 3.05 Volts", description: "", type: "decimal", range: "2.95..3.4"
		//Date/Time Stamp Events Config
		input name: "lastCheckinEnable", type: "bool", title: "Enable custom date/time stamp events for lastCheckin", description: ""
		input name: "otherDateTimeEnable", type: "bool", title: "Enable custom date/time stamp events for lastDrop, lastStationary, lastTilt, and lastVibration", description: ""
		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	displayDebugLog "Parsing sensor message: ${description}"
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def value = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	def eventType
	Map map = [:]
	def cmds = []

	// lastCheckinEpoch is for apps that can use Epoch time/date and lastCheckinTime can be used with Hubitat Dashboard
	if (lastCheckinEnable) {
		sendEvent(name: "lastCheckinEpoch", value: now())
		sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())
	}

	// Send message data to appropriate parsing function based on the type of report
	if (attrId == "0055") {
	// Handles vibration (value 01), tilt (value 02), and drop (value 03) event messages
		if (value?.endsWith('0002')) {
			eventType = 2
			parseTiltAngle(value[0..3])
		} else {
			eventType = Integer.parseInt(value,16)
		}
		map = mapSensorEvent(eventType)
	} else if (attrId == "0508") {
		// Handles XYZ Accelerometer values to determine position
		convertAccelValues(value)
	} else if (attrId == "0505") {
		// Handles recent activity level value reports
		map = mapActivityLevel(value)
	} else if (cluster == "0000" & attrId == "0005") {
		displayDebugLog "Reset button was short-pressed"
		// Change sensitivity level
		cmds = changeSensitivity()
	} else if (cluster == "0000" & (attrId == "FF01" || attrId == "FF02")) {
		// Parse battery level from hourly announcement message
		map = (value.size() > 30) ? parseBattery(value) : [:]
	} else if (!(cluster == "0000" & attrId == "0001")) {
		displayDebugLog "Unable to parse ${description}"
	}

	if (map != [:]) {
		displayDebugLog("Creating event $map")
		return createEvent(map)
	} else if (cmds != []) {
		displayDebugLog("Sending commands $cmds")
		return cmds
	} else
		return [:]
}

// Convert XYZ Accelerometer values to 3-Axis angle position
private Map convertAccelValues(value) {
	short x = (short)Integer.parseInt(value[8..11],16)
	short y = (short)Integer.parseInt(value[4..7],16)
	short z = (short)Integer.parseInt(value[0..3],16)
	float Psi = Math.round(Math.atan(x/Math.sqrt(z*z+y*y))*1800/Math.PI)/10
	float Phi = Math.round(Math.atan(y/Math.sqrt(x*x+z*z))*1800/Math.PI)/10
	float Theta = Math.round(Math.atan(z/Math.sqrt(x*x+y*y))*1800/Math.PI)/10
	def descText = "Calculated angles are Psi = ${Psi}°, Phi = ${Phi}°, Theta = ${Theta}° "
	displayDebugLog("Raw accelerometer XYZ axis values = $x, $y, $z")
	displayDebugLog(descText)
	state.currentAngleX = Psi
	state.currentAngleY = Phi
	state.currentAngleZ = Theta
	if (!state.closedX || !state.openX)
		displayInfoLog("Open/Closed position is unknown because Open and/or Closed positions have not been set")
	else {
		def float cX = Float.parseFloat(state.closedX)
		def float cY = Float.parseFloat(state.closedY)
		def float cZ = Float.parseFloat(state.closedZ)
		def float oX = Float.parseFloat(state.openX)
		def float oY = Float.parseFloat(state.openY)
		def float oZ = Float.parseFloat(state.openZ)
		// the margin of error value is used to increase/decrease the area of possible positions considered as open / closed
		def float e = (marginOfError) ? marginOfError : 10.0
		def ocPosition = "unknown"
		if ((Psi < cX + e) && (Psi > cX - e) && (Phi < cY + e) && (Phi > cY - e) && (Theta < cZ + e) && (Theta > cZ - e))
			ocPosition = "closed"
		else if ((Psi < oX + e) && (Psi > oX - e) && (Phi < oY + e) && (Phi > oY - e) && (Theta < oZ + e) && (Theta > oZ - e))
			ocPosition = "open"
		else
			displayDebugLog("The current calculated angle position does not match either stored open/closed positions")
		sendpositionEvent(ocPosition)
	}
}

// Handles Recent Activity level value messages
private Map mapActivityLevel(value) {
	def level = Integer.parseInt(value[0..3],16)
	def descText = "Recent activity level reported at $level"
	displayInfoLog(descText)
	return [
		name: 'activityLevel',
		value: level,
		descriptionText: descText,
	]
}

// Create map of values to be used for vibration, tilt, or drop event
private Map mapSensorEvent(value) {
	def seconds = (value == 1 || value == 4) ? (motionreset ? motionreset : 65) : 2
	def time = new Date(now() + (seconds * 1000))
	def statusType = ["Stationary", "Vibration", "Tilt", "Drop", "", ""]
	def eventName = ["", "motion", "acceleration", "button", "motion", "acceleration"]
	def eventType = ["", "active", "active", "pushed", "inactive", "inactive"]
	def eventMessage = ["Sensor is stationary", "Vibration/movement detected (Motion active)", "Tilt detected (Acceleration active)", "Drop detected (Button pushed)", "Motion reset to inactive after $seconds seconds", "Acceleration reset to inactive"]
	if (value < 4) {
		sendEvent(name: "sensorStatus", value: statusType[value], descriptionText: eventMessage[value])
		updateDateTimeStamp(statusType[value])
	}
	displayInfoLog("${eventMessage[value]}")
	if (value == 0)
		return
	else if (value == 1) {
		runOnce(time, clearmotionEvent)
		state.motionactive = 1
	}
	else if (value == 2)
		runOnce(time, clearaccelEvent)
	else if (value == 3)
		runOnce(time, cleardropEvent)
	return [
		name: eventName[value],
		value: eventType[value],
		descriptionText: (value > 3) ? eventMessage[value] : ""
	]
}

// Handles tilt angle change message and posts event to update UI tile display
private parseTiltAngle(value) {
	def angle = Integer.parseInt(value,16)
	def descText = "Tilt angle changed by $angle°"
	sendEvent(
		name: 'tiltAngle',
		value: angle,
		descriptionText : descText,
		isStateChange:true
	)
	displayInfoLog(descText)
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
	return [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		descriptionText: descText
	]
}

// Generate lastStationaryEpoch/Time, lastVibrationEpoch/Time, lastTiltEpoch/Time, or lastDropEpoch/Time event for Epoch time/date app or Hubitat dashboard use
def updateDateTimeStamp(timeStampType) {
	if (otherDateTimeEnable) {
		displayDebugLog("Setting last${timeStampType}Epoch and last${timeStampType}Time to current date/time")
		sendEvent(name: "last${timeStampType}Epoch", value: now(), descriptionText: "Updated button${timeStampType}Epoch")
		sendEvent(name: "last${timeStampType}Time", value: new Date().toLocaleString(), descriptionText: "Updated button${timeStampType}Time")
	}
}

def clearmotionEvent() {
	def result = [:]
	if (device.currentState('sensorStatus')?.value == "Vibration")
		mapSensorEvent(0)
	result = mapSensorEvent(4)
	state.motionactive = 0
	displayDebugLog("Sending event $result")
	sendEvent(result)
}

def clearaccelEvent() {
	def result = [:]
	if (device.currentState('sensorStatus')?.value == "Tilt") {
		if (state.motionactive == 1)
			sendEvent(name: "sensorStatus", value: "Vibration")
		else
			mapSensorEvent(0)
	}
	result = mapSensorEvent(5)
	displayDebugLog("Sending event $result")
	sendEvent(result)
}

def cleardropEvent() {
	if (device.currentState('sensorStatus')?.value == "Drop") {
		if (state.motionactive == 1)
			sendEvent(name: "sensorStatus", value: "Vibration")
		else
			mapSensorEvent(0)
	}
}

def setClosedPosition() {
	if (state.currentAngleX) {
		state.closedX = state.currentAngleX
		state.closedY = state.currentAngleY
		state.closedZ = state.currentAngleZ
		sendpositionEvent("closed")
		displayInfoLog("Closed position successfully set")
		displayDebugLog("Closed position set to $state.closedX°, $state.closedY°, $state.closedZ°")
	}
	else
		displayDebugLog("Closed position NOT set because no 3-axis accelerometer reports have been received yet")
}

def setOpenPosition() {
	if (state.currentAngleX) {
		state.openX = state.currentAngleX
		state.openY = state.currentAngleY
		state.openZ = state.currentAngleZ
		sendpositionEvent("open")
		displayInfoLog("Open position successfully set")
		displayDebugLog("Open position set to $state.openX°, $state.openY°, $state.openZ°")
	}
	else
		displayDebugLog("Open position NOT set because no 3-axis accelerometer reports have been received yet")
}

def sendpositionEvent(String ocPosition) {
	def descText = "Calculated position is $ocPosition"
	displayInfoLog(descText)
	sendEvent(name: "contact", value: ocPosition, descriptionText: descText)
}

def changeSensitivity() {
	if (!state.sensitivity)
		// set initial sensitivity level to low
		state.sensitivity = 0
	else
		// cycle level each time button is pressed - low > medium > high > low, etc.
		state.sensitivity = (state.sensitivity < 2) ? state.sensitivity + 1 : 0
	// sensitivity level attribute payload to send - low = 0x15, medium = 0x0B, high = 0x01
	def attrValue = ["15", "0B", "01"]
	def levelText = ["Low", "Medium", "High"]
	def descText = "Sensitivity level set to ${levelText[state.sensitivity]}"
	//def cmds = zigbee.writeAttribute(0x0000, 0xFF0D, 0x20, attrValue[state.sensitivity], [mfgCode: "0x115F"])
	def cmds = [
		"he wattr 0x${device.deviceNetworkId} 0x01 0x0000 0xFF0D 0x20 {${attrValue[state.sensitivity]}} {115F}", "delay 200"
	]
	sendEvent(name: "sensitivityLevel", value: levelText[state.sensitivity], descriptionText: descText)
	displayInfoLog(descText)
	return cmds
}

// installed() runs just after a sensor is paired
def installed() {
	displayInfoLog("Installing")
	state.prefsSetCount = 0
	mapSensorEvent(0)
	refresh()
	init()
}

// configure() runs after installed() when a sensor is paired
def configure() {
	displayInfoLog("Configuring")
	mapSensorEvent(0)
	refresh()
	init()
	state.prefsSetCount = 1
}

// updated() runs every time user presses save in preference settings page
def updated() {
	displayInfoLog("Updating preference settings")
	init()
	refresh()
	if (lastCheckinEnable)
		displayInfoLog("Last checkin events enabled")
	if (otherDateTimeEnable)
		displayInfoLog("Other date/time stamp events enabled")
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def init() {
	if (!device.currentValue('batteryLastReplaced'))
		resetBatteryReplacedDate(true)
	if (!state.sensitivity) {
		state.sensitivity = 2
		log.info "Please short-press reset button to set initial sensitivity level to Low"
	}
	sendEvent(name: "numberOfButtons", value: 1)
}

def refresh() {
	displayInfoLog("Refreshing UI display")
	if (device.currentValue('tiltAngle') == null)
		sendEvent(name: 'tiltAngle', value: "--")
	if (device.currentValue('activityLevel') == null)
		sendEvent(name: 'activityLevel', value: "--")
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: new Date().format("MMM dd yyyy", location.timeZone))
	displayInfoLog("Setting Battery Last Replaced to current date${newlyPaired}")
}

private def displayDebugLog(message) {
	if (debugLogging)
		log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}
