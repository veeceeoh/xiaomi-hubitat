/**
 *  IMPORT URL: https://raw.githubusercontent.com/veeceeoh/xiaomi-hubitat/master/devicedrivers/xiaomi-cube-controller-hubitat.src/xiaomi-aqara-wireless-switch.groovy
 *
 *  Xiaomi Mi Cube Controller - model MFKZQ01LM
 *  Device Driver for Hubitat Elevation hub
 *  Version: 0.3.3b
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
 *  Based on original SmartThings device handler code by Oleg "DroidSector" Smirnov and Artur Draga
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, mike.maxwell, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *  Reworked and additional code for use with Hubitat Elevation hub by veeceeoh
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 *  + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *    Holding the Cube's reset button (inside, next to the battery) until the LED blinks will start pairing mode.
 *    3 quick flashes indicates success, while one long flash means pairing has not started yet.
 *    In either case, keep the sensor "awake" by short-pressing the reset button repeatedly, until recognized by Hubitat.
 *  + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, and follow
 *    the same steps for pairing. As long as it has not been removed from the Hubitat's device list, when the LED
 *    flashes 3 times, the sensor should be reconnected and will resume reporting as normal
 *
 */

metadata {
	definition (name: "Xiaomi Mi Cube Controller", namespace: "veeceeoh", author: "veeceeoh", importUrl: "https://raw.githubusercontent.com/veeceeoh/xiaomi-hubitat/master/devicedrivers/xiaomi-cube-controller-hubitat.src/xiaomi-aqara-wireless-switch.groovy") {
		capability "Battery"
		capability "Configuration"
		capability "PushableButton"
		capability "Three Axis"
		capability "Sensor"

		attribute "angle", "number"
		attribute "face", "number"
		attribute "lastCheckinEpoch", "String"
		attribute "lastCheckinTime", "String"
		attribute "batteryLastReplaced", "String"

		// Fingerprint data used to match driver to device during pairing
		fingerprint profileId: "0104", inClusters: "0000,0003,0019,0012", outClusters: "0000,0004,0003,0005,0019,0012", deviceJoinName: "Xiaomi Mi Cube"
		fingerprint profileId: "0104", inClusters: "0000,0003,0019,0012", outClusters: "0000,0004,0003,0005,0019,0012", model: "lumi.sensor_cube", deviceJoinName: "Xiaomi Mi Cube"
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,0012", outClusters: "0000,0004,0003,0005,0019,0012", model: "lumi.sensor_cube", deviceJoinName: "Xiaomi Mi Cube"

		command "setFace0"
		command "setFace1"
		command "setFace2"
		command "setFace3"
		command "setFace4"
		command "setFace5"
		command "flip90"
		command "flip180"
		command "slide"
		command "knock"
		command "rotateR"
		command "rotateL"
		command "shake"
		command "resetBatteryReplacedDate"
	}

	preferences {
		//Cube Mode Config
		input (name: "cubeMode", title: "Cube Mode: Select how many buttons to control", description: "", type: "enum", options: [0: "Simple - 7 buttons", 1: "Advanced - 36 buttons", 2: "Combined - 43 buttons"])
		//Battery Voltage Range
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.9). Default = 2.9 Volts", description: "", type: "decimal", range: "2..2.9"
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.95 to 3.4). Default = 3.05 Volts", description: "", type: "decimal", range: "2.95..3.4"
		//Date/Time Stamp Events Config
		input name: "lastCheckinEnable", type: "bool", title: "Enable custom date/time stamp events for lastCheckin", description: ""
		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
		//Firmware 2.0.5 Compatibility Fix Config
		input name: "oldFirmware", type: "bool", title: "DISABLE 2.0.5 firmware compatibility fix (for users of 2.0.4 or earlier)", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	displayDebugLog("Parsing message: ${description}")
	Map eventMap = [:]
	if (description?.startsWith('re')) {
		description = description - "read attr - "
		Map descMap = (description).split(",").inject([:]) {
			map, param ->
			def nameAndValue = param.split(":")
			map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
		}
		displayDebugLog("Map of message: ${descMap}")
		def intEncoding = Integer.parseInt(descMap.encoding, 16)
		if (!oldFirmware && descMap.value != null && intEncoding > 0x18 && intEncoding < 0x3e) {
			displayDebugLog("Data type of message payload is little-endian; reversing byte order")
			// Reverse order of bytes in description's payload for LE data types - required for Hubitat firmware 2.0.5 or newer
			descMap.value = reverseHexString(descMap.value)
			displayDebugLog("Reversed payload value: ${descMap.value}")
		}
		// Send message data to appropriate parsing function based on the type of report
		if (descMap.cluster == "0012" && descMap.attrId == "0055") {
			// Shake, flip, knock, slide messages
			getMotionResult(descMap.value)
		} else if (descMap.cluster == "000C" && descMap.attrId == "FF05") {
			// Rotation (90 and 180 degrees)
			getRotationResult(descMap.value)
		} else if (descMap.cluster == "0000" && descMap.attrId == "0005") {
			displayInfoLog("Reset button was short-pressed")
			// Parse battery level from longer type of announcement message
			eventMap = (descMap.value.size() > 60) ? parseBattery(descMap.value.split('FF42')[1]) : [:]
		} else if (descMap.cluster == "0000" & (descMap.attrId == "FF01" || descMap.attrId == "FF02")) {
			// Parse battery level from hourly announcement message
			eventMap = (descMap.value.size() > 30) ? parseBattery(descMap.value) : [:]
		} else
			displayDebugLog("Unable to parse message")
	} else if (description?.startsWith('cat')) {
		displayDebugLog("No action taken on 'catchall' message")
	} else
		displayDebugLog("Unknown message type, message not parseable")
	if (eventMap != [:]) {
		displayInfoLog(eventMap.descriptionText)
		displayDebugLog("Creating event $eventMap")
		return createEvent(eventMap)
	} else
		return eventMap
}

// Reverses order of bytes in hex string
def reverseHexString(hexString) {
	def reversed = ""
	for (int i = hexString.length(); i > 0; i -= 2) {
		reversed += hexString.substring(i - 2, i )
	}
	return reversed
}

private Map getMotionResult(String value) {
	String motionType = value[0..1]
	String binaryValue = hexToBin(value[2..3])
	Integer sourceFace = Integer.parseInt(binaryValue[2..4],2)
	Integer targetFace = Integer.parseInt(binaryValue[5..7],2)
	displayDebugLog("motionType: $motionType, binaryValue: $binaryValue, sourceFace: $sourceFace, targetFace: $targetFace")
	if (motionType == "00") {
		switch(binaryValue[0..1]) {
			case "00":
				if (targetFace == 0)
					shakeEvents()
				break
			case "01":
				flipEvents(targetFace, "90")
				break
			case "10":
				flipEvents(targetFace, "180")
				break
		}
	} else if (motionType == "01") {
		slideEvents(targetFace)
	} else if (motionType == "02") {
		knockEvents(targetFace)
	}
}

private Map getRotationResult(value) {
	Integer angle = Math.round(Float.intBitsToFloat(Long.parseLong(value[0..7],16).intValue()))
	rotateEvents(angle)
}

def String hexToBin(String thisByte, Integer size = 8) {
	String binaryValue = new BigInteger(thisByte, 16).toString(2);
	return String.format("%${size}s", binaryValue).replace(' ', '0')
}

def Map shakeEvents() {
	def descText
	if (!settings.cubeMode || settings.cubeMode in ['0','2'] ) {
		descText = (settings.cubeMode == '0') ? "Shake detected (button 1 pushed)" : null
		sendEvent([
			name: "pushed",
			value: 1,
			data: [face: device.currentValue("face")],
			descriptionText: descText,
			isStateChange: true
		])
		if (descText)
			displayInfoLog(descText)
	}
	if (settings.cubeMode in ['1','2'] ){
		def buttonNum = (device.currentValue("face") as Integer) + ((settings.cubeMode == '1') ? 31 : 38)
		descText = "Shake detected with face #${device.currentValue("face")} up (button $buttonNum pushed)"
		sendEvent([
			name: "pushed",
			value: buttonNum,
			data: [face: device.currentValue("face")],
			descriptionText: descText,
			isStateChange: true
		])
		displayInfoLog(descText)
	}
}

def flipEvents(Integer faceId, String flipType) {
	def descText
	if (flipType == "0") {
		sendEvent([
			name: 'face',
			value: -1,
			isStateChange: false
		])
		sendEvent([
			name: 'face',
			value: faceId,
			isStateChange: false
		])
	} else if (flipType == "90") {
		descText = (settings.cubeMode == '0') ? "90° flip detected (button 2 pushed)" : null
		if (settings.cubeMode in ['0','2']) {
			sendEvent([
				name: "pushed",
				value: 2 ,
				data: [face: faceId],
				descriptionText: descText,
				isStateChange: true
			])
			if (descText)
				displayInfoLog(descText)
		}
	} else if (flipType == "180") {
		descText = (settings.cubeMode == '0') ? "180° flip detected (button 3 pushed)" : null
		if (settings.cubeMode in ['0','2']) {
			sendEvent([
				name: "pushed",
				value: 3 ,
				data: [face: faceId],
				descriptionText: descText,
				isStateChange: true
			])
			if (descText)
				displayInfoLog(descText)
		}
	}
	sendEvent([
		name: 'face',
		value: faceId,
		displayed: false
	])
	if (settings.cubeMode in ['1','2']) {
		def buttonNum = faceId + ((settings.cubeMode == '1') ? 1 : 8)
		descText = "Flip to face #$faceId detected (button $buttonNum pushed)"
		sendEvent([
			name: "pushed",
			value: buttonNum,
			data: [face: faceId],
			descriptionText: descText,
			isStateChange: true
		])
		displayInfoLog(descText)
	}
	switch (faceId) {
		case 0: sendEvent([name: "threeAxis", value: "[x:0,y:-1000,z:0]"]); break
		case 1: sendEvent([name: "threeAxis", value: "[x:-1000,y:0,z:0]"]); break
		case 2: sendEvent([name: "threeAxis", value: "[x:0,y:0,z:1000]"]); break
		case 3: sendEvent([name: "threeAxis", value: "[x:1000,y:0,z:0]"]); break
		case 4: sendEvent([name: "threeAxis", value: "[x:0,y:1000,z:0]"]); break
		case 5: sendEvent([name: "threeAxis", value: "[x:0,y:0,z:-1000]"]); break
	}
}

def Map slideEvents(Integer targetFace) {
	def descText
	if (targetFace != device.currentValue("face") as Integer) {
		displayInfoLog("Stale face data, updating")
		setFace(targetFace)
	}
	if (!settings.cubeMode || settings.cubeMode in ['0','2']) {
		descText = (settings.cubeMode == '0') ? "Slide detected (button 4 pushed)" : null
		sendEvent([
			name: "pushed",
			value: 4,
			data: [face: targetFace],
			descriptionText: descText,
			isStateChange: true
		])
		if (descText)
			displayInfoLog(descText)
	}
	if (settings.cubeMode in ['1','2']) {
		def buttonNum = targetFace+((settings.cubeMode == '1') ? 7 : 14)
		descText = "Slide detected with face #$targetFace up (button $buttonNum pushed)"
		sendEvent([
			name: "pushed",
			value: buttonNum,
			data: [face: targetFace],
			descriptionText: descText,
			isStateChange: true
		])
		displayInfoLog(descText)
	}
}

def knockEvents(Integer targetFace) {
	def descText
	if (targetFace != device.currentValue("face") as Integer) {
		displayInfoLog("Stale face data, updating")
		setFace(targetFace)
	}
	if (!settings.cubeMode || settings.cubeMode in ['0','2']) {
		descText = (settings.cubeMode == '0') ? "Knock detected (button 5 pushed)" : null
		sendEvent([
			name: "pushed",
			value: 5,
			data: [face: targetFace],
			descriptionText: descText,
			isStateChange: true
		])
		if (descText)
			displayInfoLog(descText)
	}
	if (settings.cubeMode in ['1','2']) {
		def buttonNum = targetFace+((settings.cubeMode == '1') ? 13 : 20)
		descText = "Knock detected with face #$targetFace up (button $buttonNum pushed)"
		sendEvent([
			name: "pushed",
			value: buttonNum,
			data: [face: targetFace],
			descriptionText: descText,
			isStateChange: true
		])
		displayInfoLog(descText)
	 }
}

def rotateEvents(Integer angle) {
	sendEvent([
		name: "angle",
		value: angle,
		isStateChange: true
	])
	displayInfoLog("Rotated by $angle°")
	def descText
	def buttonNum
	if (angle > 0) {
		if (!settings.cubeMode || settings.cubeMode in ['0','2'] ) {
			descText = (settings.cubeMode == '0') ? "Right rotation (button 6 pushed)" : null
			sendEvent([
				name: "pushed",
				value: 6,
				data: [face: device.currentValue("face"), angle: angle],
				descriptionText: descText,
				isStateChange: true
			])
			if (descText)
				displayInfoLog(descText)
		}
		if (settings.cubeMode in ['1','2']) {
			buttonNum = (device.currentValue("face") as Integer) + ((settings.cubeMode == '1') ? 19 : 26)
			descText = "Right rotation on face #${device.currentValue("face")} (button $buttonNum pushed)"
			sendEvent([
				name: "pushed",
				value: buttonNum,
				data: [face: device.currentValue("face")],
				descriptionText: descText,
				isStateChange: true
			])
			displayInfoLog(descText)
		}
	} else {
		if (!settings.cubeMode || settings.cubeMode in ['0','2']) {
			descText = (settings.cubeMode == '0') ? "Left rotation (button 7 pushed)" : null
			sendEvent([
				name: "pushed",
				value: 7,
				data: [face: device.currentValue("face"), angle: angle],
				descriptionText: descText,
				isStateChange: true
			])
			if (descText)
				displayInfoLog(descText)
		}
		if (settings.cubeMode in ['1','2']) {
			buttonNum = (device.currentValue("face") as Integer) + ((settings.cubeMode == '1') ? 25 : 32)
			descText = "Left rotation on face #${device.currentValue("face")} (button $buttonNum pushed)"
			sendEvent([
				name: "pushed",
				value: buttonNum,
				data: [face: device.currentValue("face")],
				descriptionText: descText,
				isStateChange: true
			])
			displayInfoLog(descText)
		}
	}
}

def setFace(Integer faceId) {
	def Integer prevFaceId = device.currentValue("face")
	if (prevFaceId == faceId) {
		flipEvents(faceId, "0")
	} else if ((prevFaceId == 0 && faceId == 3)||(prevFaceId == 1 && faceId == 4)||(prevFaceId == 2 && faceId == 5)||(prevFaceId == 3 && faceId == 0)||(prevFaceId == 4 && faceId == 1)||(prevFaceId == 5 && faceId == 2)){
		flipEvents(faceId, "180")
	} else {
		flipEvents(faceId, "90")
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
	def minVolts = voltsmin ? voltsmin : 2.9
	def maxVolts = voltsmax ? voltsmax : 3.05
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	def descText = "Battery level is ${roundedPct}% (${rawVolts} Volts)"
	// lastCheckinEpoch is for apps that can use Epoch time/date and lastCheckinTime can be used with Hubitat Dashboard
	if (lastCheckinEnable) {
		sendEvent(name: "lastCheckinEpoch", value: now())
		sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())
	}
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		descriptionText: descText
	]
	return result
}

private def displayDebugLog(message) {
	if (debugLogging)
		log.debug "${device.displayName}: ${message}"
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

// installed() runs just after a device is paired
def installed() {
	state.prefsSetCount = 0
	displayInfoLog("Installing")
}

// configure() runs after installed() when a device is paired or reconnected
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

// Set number of buttons available to Apps based on user setting
def setNumButtons() {
	def numButtons = [7, 36, 43]
	def cubeModeInt = (settings.cubeMode) ? settings.cubeMode as Integer : null
	if (settings.cubeMode && (state.cubeMode != cubeModeInt)) {
		sendEvent(name: "numberOfButtons", value: numButtons[cubeModeInt])
		state.cubeMode = cubeModeInt
		displayInfoLog("Number of buttons set to ${numButtons[cubeModeInt]}")
	} else if (state.cubeMode ==  null || (settings.cubeMode == null && state.cubeMode != 0)) {
		sendEvent(name: "numberOfButtons", value: 7)
		state.cubeMode = 0
		displayInfoLog("Number of buttons set to default of 7")
	}
}

// This section is functions used for driver commands
def setFace0() {
	setFace(0)
}
def setFace1() {
	setFace(1)
}
def setFace2() {
	setFace(2)
}
def setFace3() {
	setFace(3)
}
def setFace4() {
	setFace(4)
}
def setFace5() {
	setFace(5)
}
def flip90() {
	def flipMap = [0:5, 1:2, 2:0, 3:2, 4:5, 5:3]
	flipEvents(flipMap[device.currentValue("face") as Integer], "90")
}
def flip180() {
	def flipMap = [0:3, 1:4, 2:5, 3:0, 4:1, 5:2]
	flipEvents(flipMap[device.currentValue("face") as Integer], "180")
}
def rotateL() {
	rotateEvents(-90)
}
def rotateR() {
	rotateEvents(90)
}
def slide() {
	slideEvents(device.currentValue("face") as Integer)
}
def knock() {
	knockEvents(device.currentValue("face") as Integer)
}
def shake() {
	shakeEvents()
}
