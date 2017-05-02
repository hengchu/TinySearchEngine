package com.tinysearchengine.indexer.test;

import java.io.IOException;
import java.io.PrintWriter;

public class GenerateTestInputData {
	public static void main(String[] args)
	{
		try{
		    PrintWriter writer = new PrintWriter(
		    		"filesForTest/invertedIndexer/inputsoh/testinput01.txt", 
		    		"UTF-8");
		    writer.println("http://video.statesman.com/See-How-This-Bride-Made-A-Puppy-Bouquet-For-A-Good-Cause-32307855\001{\"s3\":{\"bucket\":\"tinysearchengine\",\"key\":\"video.statesman.com/See-How-This-Bride-Made-A-Puppy-Bouquet-For-A-Good-Cause-32307855\",\"region\":\"us-east-2\"}}\001{}");
		    writer.println("http://www.google.com/finance?q=USDHKD&ei=Nm_-WKmWEJHwjAHf_ZuYCg\001{\"s3\":{\"bucket\":\"tinysearchengine\",\"key\":\"www.google.com/finance?q=USDHKD&ei=Nm_-WKmWEJHwjAHf_ZuYCg\",\"region\":\"us-east-2\"}}\001{}");
		    writer.println("http://info.yahoo.com/ie/el/yahoo/privacy/index.html\001{\"s3\":{\"bucket\":\"tinysearchengine\",\"key\":\"info.yahoo.com/ie/el/yahoo/privacy/index.html\",\"region\":\"us-east-2\"}}\001{}");
		    writer.println("http://kinja.com/ericvanallen\001{\"s3\":{\"bucket\":\"tinysearchengine\",\"key\":\"kinja.com/ericvanallen\",\"region\":\"us-east-2\"}}\001{}");
		    
		    writer.close();
		} catch (IOException e) {
		   // do something
		}
	}
}
