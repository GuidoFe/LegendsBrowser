package legends.model;

import legends.Application;

public class DanceForm extends ArtForm {
	public String getUrl() {
		return Application.getSubUri() + "/danceform/" + id;
	}

	public String getLink() {
		if(id==-1)
			return "<i>UNKNOWN DANCE FORM</i>";
		return "<a href=\"" + getUrl() + "\" class=\"art-form danceform\"><i class=\"fa fa-dot-circle-o\"></i> " + getName() + "</a>";
	}
	
	@Override
	public String getType() {
		return "dance form";
	}
}
