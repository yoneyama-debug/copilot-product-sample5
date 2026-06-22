package jp.co.sstinc.lsimpledev

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import jp.co.sstinc.lsimpledev.databinding.ActivityMainBinding
import com.google.android.gms.oss.licenses.v2.OssLicensesMenuActivity


class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private var shouldShowTopMenuItems = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.activityMain) { v, insets ->
            val systembars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systembars.left, systembars.top, systembars.right, systembars.bottom)
            insets
        }

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            shouldShowTopMenuItems = when(destination.id) {
                R.id.FileOutputSettingsFragment,
                R.id.ServerSettingsFragment -> false
                else -> true
            }
            invalidateOptionsMenu()
        }

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        MenuCompat.setGroupDividerEnabled(menu, true)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_settings)?.isVisible = shouldShowTopMenuItems
        menu.findItem(R.id.action_file_output_settings)?.isVisible = shouldShowTopMenuItems
        menu.findItem(R.id.action_server_settings)?.isVisible = shouldShowTopMenuItems
        menu.findItem(R.id.action_oss_licenses)?.isVisible = shouldShowTopMenuItems
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        val navController = findNavController(R.id.nav_host_fragment_content_main)

        return when (item.itemId) {
            R.id.action_settings -> {

                val intent = Intent().apply {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)

                true
            }

            R.id.action_file_output_settings -> {
                // FileOutputSettingsFragmentに遷移する
                if(navController.currentDestination?.id != R.id.FileOutputSettingsFragment) {
                    navController.navigate(R.id.FileOutputSettingsFragment)
                }
                true
            }

            R.id.action_server_settings -> {
                // ServerSettingsFragmentに遷移する
                if(navController.currentDestination?.id != R.id.ServerSettingsFragment) {
                    navController.navigate(R.id.ServerSettingsFragment)
                }
                true
            }

            R.id.action_oss_licenses -> {
                OssLicensesMenuActivity.setActivityTitle(getString(R.string.action_oss_licenses))
                startActivity(Intent(this, OssLicensesMenuActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }



    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}