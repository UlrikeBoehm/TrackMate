package fiji.plugin.nperry;

public enum Feature {
	VARIANCE,
	LOG_VALUE,
	BRIGHTNESS,
	CONTRAST;
	
	public double getScore(double value) {
		switch (this) {
			case VARIANCE:
				return 1/value;
			case LOG_VALUE:
				return value;
			case BRIGHTNESS:
				return value;
			case CONTRAST:
				return value;
			default:
				return 0;
		}
	}
	
	public String getName() {
		switch(this) {
		case VARIANCE:
			return "Variance";
		case LOG_VALUE:
			return "LoG Value";
		case BRIGHTNESS:
			return "Brightness";
		case CONTRAST:
			return "Contrast";
		default:
			return null;
		}
	}
}