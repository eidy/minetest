package net.minetest.minetest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Vector;
import java.util.Iterator;
import java.lang.Object;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;

import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Display;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.graphics.Rect;
import android.graphics.Paint;
import android.text.TextPaint;
import android.content.SharedPreferences;
import android.view.WindowManager;


public class MinetestAssetCopy extends Activity
{

    String m_CacheDir;

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.assetcopy);

        m_ProgressBar = (ProgressBar) findViewById(R.id.progressBar1);
        m_Filename = (TextView) findViewById(R.id.textView1);

        Display display = getWindowManager().getDefaultDisplay();
        m_ProgressBar.getLayoutParams().width = (int) (display.getWidth() * 0.8);
        m_ProgressBar.invalidate();
        m_CacheDir = getApplicationContext().getCacheDir().getAbsolutePath();
        Log.i("MinetestAssetCopy", "CacheDir = " + m_CacheDir);

        if (!isNewInstall())
        {
            Log.i("MinetestAssetCopy", "Detected Version : " + getInstalledVersion() + " - no version update required.");
            finish();
            return;
        }

		/* check if there's already a copy in progress and reuse in case it is*/
        MinetestAssetCopy prevActivity =
                (MinetestAssetCopy) getLastNonConfigurationInstance();
        if (prevActivity != null)
        {
            m_AssetCopy = prevActivity.m_AssetCopy;
        } else
        {
            m_AssetCopy = new copyAssetTask();
            m_AssetCopy.execute();
        }

    }

    /* preserve asset copy background task to prevent restart of copying */
    /* this way of doing it is not recommended for latest android version */
	/* but the recommended way isn't available on android 2.x */
    public Object onRetainNonConfigurationInstance()
    {
        return this;
    }

    private String getPackageVersion()
    {

        String version = "";
        try
        {
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(this.getPackageName(), 0);
            version = pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e)
        {
            e.printStackTrace();
        }

        return R.string.buildid + version;
    }

    private String getInstalledVersion()
    {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        return prefs.getString("installVersion", "");
    }
    private boolean isNewInstall()
    {

        String storedVersion = getInstalledVersion();
        String currentVersion = getPackageVersion();
        return (currentVersion != storedVersion);
    }

    private void updateInstallVersion()
    {


        SharedPreferences sharedPref = getPreferences(MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString("installVersion", getPackageVersion());
        editor.commit();
        Log.i("MinetestAssetCopy", "Wrote Version : " + getPackageVersion());


    }

    ProgressBar m_ProgressBar;
    TextView m_Filename;

    copyAssetTask m_AssetCopy;

    private class copyAssetTask extends AsyncTask<String, Integer, String>
    {

        private long getFullSize(String filename)
        {
            long size = 0;
            try
            {
                InputStream src = getAssets().open(filename);
                byte[] buf = new byte[4096];

                int len = 0;
                while ((len = src.read(buf)) > 0)
                {
                    size += len;
                }
            } catch (IOException e)
            {
                e.printStackTrace();
            }
            return size;
        }

        // Get filename from the path
        private String GetFilename(String str)
        {
            if (null != str && str.length() > 0)
            {
                int endIndex = str.lastIndexOf("/");
                if (endIndex != -1)
                {
                    String newstr = str.substring(endIndex + 1);
                    return newstr;
                }
            }
            return str;
        }

        @Override
        protected String doInBackground(String... files)
        {
            m_foldernames = new Vector<String>();
            m_filenames = new Vector<String>();
            m_tocopy = new Vector<String>();
            m_asset_size_unknown = new Vector<String>();
            String baseDir =
                    Environment.getExternalStorageDirectory().getAbsolutePath()
                            + "/";
            String cacheDir = m_CacheDir + "/media/";


            // If it's a new install, delete the cache
            if (isNewInstall())
            {
                File mediaCacheObj = new File(m_CacheDir);
                if (mediaCacheObj.exists() && mediaCacheObj.isDirectory())
                {
                    deleteRecursive(mediaCacheObj);
                }
            }

            // prepare temp folder
            File TempFolder = new File(baseDir + "eidy/tmp/");

            if (!TempFolder.exists())
            {
                TempFolder.mkdir();
            } else
            {
                File[] todel = TempFolder.listFiles();

                for (int i = 0; i < todel.length; i++)
                {
                    Log.v("MinetestAssetCopy", "deleting: " + todel[i].getAbsolutePath());
                    todel[i].delete();
                }
            }


            // Look for Dev Mode Marker File
            File devModeMarker = new File(baseDir + "eidy/.nocopy");
            if (devModeMarker.exists())
            {
                Log.i("MinetestAssetCopy", ".nocopy (Developer?) file detected.  File copy operation cancelled.");
                return "";
            }

            // build lists from prepared data
            BuildFolderList();

            // Clean up folders
            RemoveUnusedFolders();

            // Get a list of files to copy
            BuildFileList();

            // scan filelist
            ProcessFileList();

            // doing work
            m_copy_started = true;
            m_ProgressBar.setMax(m_tocopy.size());

            for (int i = 0; i < m_tocopy.size(); i++)
            {

                try
                {
                    String filename = m_tocopy.get(i);


                    publishProgress(i);

                    boolean asset_size_unknown = false;
                    long filesize = -1;

                    if (m_asset_size_unknown.contains(filename))
                    {
                        File testme = new File(baseDir + "/" + filename);

                        if (testme.exists())
                        {
                            filesize = testme.length();
                        }
                        asset_size_unknown = true;
                    }

                    InputStream src;
                    try
                    {
                        src = getAssets().open(filename);
                    } catch (IOException e)
                    {
                        Log.e("MinetestAssetCopy", "Copying file: " + filename + " FAILED (not in assets)");
                        e.printStackTrace();
                        continue;
                    }

                    // Transfer bytes from in to out
                    byte[] buf = new byte[1 * 1024];
                    int len = src.read(buf, 0, 1024);
					
					/* following handling is crazy but we need to deal with    */
					/* compressed assets.Flash chips limited livetime due to   */
					/* write operations, we can't allow large files to destroy */
					/* users flash.                                            */
                    if (asset_size_unknown)
                    {
                        if ((len > 0) && (len < buf.length) && (len == filesize))
                        {
                            src.close();
                            continue;
                        }

                        if (len == buf.length)
                        {
                            src.close();
                            long size = getFullSize(filename);
                            if (size == filesize)
                            {
                                continue;
                            }
                            src = getAssets().open(filename);
                            len = src.read(buf, 0, 1024);
                        }
                    }
                    if (len > 0)
                    {
                        int total_filesize = 0;
                        OutputStream dst;
                        try
                        {
                            if (filename.contains("/cache/"))
                            {
                                dst = new FileOutputStream(cacheDir + "/" + GetFilename(filename));
                            } else
                            {
                                dst = new FileOutputStream(baseDir + "/" + filename);
                            }
                        } catch (IOException e)
                        {
                            Log.e("MinetestAssetCopy", "Copying file: " + baseDir +
                                    "/" + filename + " FAILED (couldn't open output file)");
                            e.printStackTrace();
                            src.close();
                            continue;
                        }
                        dst.write(buf, 0, len);
                        total_filesize += len;

                        while ((len = src.read(buf)) > 0)
                        {
                            dst.write(buf, 0, len);
                            total_filesize += len;
                        }

                        dst.close();
                        Log.v("MinetestAssetCopy", "Copied file: " +
                                m_tocopy.get(i) + " (" + total_filesize +
                                " bytes)");
                    } else if (len < 0)
                    {
                        Log.e("MinetestAssetCopy", "Copying file: " +
                                m_tocopy.get(i) + " failed, size < 0");
                    }
                    src.close();
                } catch (IOException e)
                {
                    Log.e("MinetestAssetCopy", "Copying file: " +
                            m_tocopy.get(i) + " failed");
                    e.printStackTrace();
                }
            }

            updateInstallVersion();
            return "";
        }


        /**
         * update progress bar
         */
        protected void onProgressUpdate(Integer... progress)
        {

            if (m_copy_started)
            {
                boolean shortened = false;
                String todisplay = m_tocopy.get(progress[0]);
                m_ProgressBar.setProgress(progress[0]);
                m_Filename.setText(todisplay);
            } else
            {
                boolean shortened = false;
                String todisplay = m_Foldername;
                String full_text = "scanning " + todisplay + " ...";
                m_Filename.setText(full_text);
            }
        }

        void deleteRecursive(File fileOrDirectory)
        {
            if (fileOrDirectory.isDirectory())
                for (File child : fileOrDirectory.listFiles())
                    deleteRecursive(child);

            fileOrDirectory.delete();
        }

        private void RemoveUnusedFolders()
        {
            // Go through folder list and remove any that aren't in the current asset
            // folder
            String FlashBaseDir =
                    Environment.getExternalStorageDirectory().getAbsolutePath();
            int basedirlen = FlashBaseDir.length() + 1;

            // Hardcoded to mods folder for now
            File mods_folder = new File(FlashBaseDir + "/eidy/games/eidy/mods");

            // Get entries under this folder
            File[] files = mods_folder.listFiles();
            if (files != null && files.length > 0)
            {
                Log.i("MinetestAssetCopy", "\t Previously existing files detected!");
            } else
            {
                Log.i("MinetestAssetCopy", "\t Previously existing files not detected - Must be a new install.");
                return;
            }

            // Go through each mod, item seeing if it's a folder
            for (File inFile : files)
            {
                if (inFile.isDirectory())
                {
                    // See if this folder exists in foldernames
                    String strippedPath = inFile.getAbsolutePath().substring(basedirlen);

                    if (!m_foldernames.contains(strippedPath))
                    {
                        // If it's not in the index, delete it
                        Log.i("MinetestAssetCopy", "\t removed old folder: " +
                                inFile.getAbsolutePath());
                        deleteRecursive(inFile);
                    } else
                    {
                        Log.v("MinetestAssetCopy", "\t not deleted: " +
                                inFile.getAbsolutePath());
                    }
                }
            }

        }

        /**
         * check al files and folders in filelist
         */
        protected void ProcessFileList()
        {
            String FlashBaseDir =
                    Environment.getExternalStorageDirectory().getAbsolutePath();
            String CacheBaseDir = m_CacheDir + "/media/";
            Iterator itr = m_filenames.iterator();

            while (itr.hasNext())
            {
                String current_path = (String) itr.next();
                String FlashPath = FlashBaseDir + "/" + current_path;

                if (current_path.contains("/cache/"))
                {
                    FlashPath = CacheBaseDir + GetFilename(current_path);
                }

                if (isAssetFolder(current_path))
                {
					/* store information and update gui */
                    m_Foldername = current_path;
                    publishProgress(0);
					
					/* open file in order to check if it's a folder */
                    File current_folder = new File(FlashPath);
                    if (!current_folder.exists())
                    {
                        if (!current_folder.mkdirs())
                        {
                            Log.e("MinetestAssetCopy", "\t failed create folder: " +
                                    FlashPath);
                        } else
                        {
                            Log.v("MinetestAssetCopy", "\t created folder: " +
                                    FlashPath);

                            // add a .nomedia file
                            if (FlashPath.endsWith("/textures"))
                            {
                                try
                                {
                                    OutputStream dst = new FileOutputStream(FlashPath + "/.nomedia");
                                    dst.close();
                                } catch (IOException e)
                                {
                                    Log.e("MinetestAssetCopy", "Failed to create .nomedia file");
                                    e.printStackTrace();
                                }
                            }

                        }
                    }

                    continue;
                }
				
				/* if it's not a folder it's most likely a file */
                boolean refresh = true;

                File testme = new File(FlashPath);

                long asset_filesize = -1;
                long stored_filesize = -1;

                if (testme.exists())
                {
                    try
                    {
                        AssetFileDescriptor fd = getAssets().openFd(current_path);
                        asset_filesize = fd.getLength();
                        fd.close();
                    } catch (IOException e)
                    {
                        refresh = true;
                        m_asset_size_unknown.add(current_path);
                        Log.e("MinetestAssetCopy", "Failed to open asset file \"" +
                                FlashPath + "\" for size check");
                    }

                    stored_filesize = testme.length();

                    if (asset_filesize == stored_filesize)
                    {
                        refresh = false;
                    }

                }

                if (refresh)
                {
                    m_tocopy.add(current_path);
                }
            }
        }

        /**
         * read list of folders prepared on package build
         */
        protected void BuildFolderList()
        {
            try
            {
                InputStream is = getAssets().open("index.txt");
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));

                String line = reader.readLine();
                while (line != null)
                {
                    m_foldernames.add(line);
                    line = reader.readLine();
                }
                is.close();
            } catch (IOException e1)
            {
                Log.e("MinetestAssetCopy", "Error on processing index.txt");
                e1.printStackTrace();
            }
        }

        /**
         * read list of asset files prepared on package build
         */
        protected void BuildFileList()
        {
            long entrycount = 0;
            try
            {
                InputStream is = getAssets().open("filelist.txt");
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));

                String line = reader.readLine();
                while (line != null)
                {
                    m_filenames.add(line);
                    line = reader.readLine();
                    entrycount++;
                }
                is.close();
            } catch (IOException e1)
            {
                Log.e("MinetestAssetCopy", "Error on processing filelist.txt");
                e1.printStackTrace();
            }
        }

        @Override
        protected void onPostExecute(String result)
        {

            finish();
        }

        protected boolean isAssetFolder(String path)
        {
            return m_foldernames.contains(path);
        }

        boolean m_copy_started = false;
        String m_Foldername = "media";
        Vector<String> m_foldernames;
        Vector<String> m_filenames;
        Vector<String> m_tocopy;
        Vector<String> m_asset_size_unknown;
    }
}
