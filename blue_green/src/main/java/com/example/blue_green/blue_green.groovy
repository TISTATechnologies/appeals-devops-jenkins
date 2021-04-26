#!/usr/bin/env groovy
// Can probably remove the line below
// package com.example.blue_green;
import groovy.json.JsonSlurper
//TODO: Figure out logging. Right now there are a lot of print statements
/**
 * Hello world!
 *
 *
 * public class App 
 * {
 *     public static void main( String[] args )
 *     {
 *         System.out.println( "Hello World!" );
 *     }
 * }

 * All of these in one file (skip step 2 for the moment)
 * STAGE0 monitoring (paralell to all other stages) (in the future)
 *        if any failure ABORT

 * STAGE1 deploy_green
 * get_outputs()
 * get_blue()
 * change attach_asg_to var, apply 
 * deploy green() // wait for green to respond with 200s
  
 * STAGE2 smoke_testing
 * run_tests(jenkins var for running the tests)
 
 * STAGE3 transition
 * weight_shift() // this is done via the weights in grunt with 200s
 * destroy_old_blue() 
 * next_deploy_prep() // change green values to correct green values and apply
*/
// TODO: remove this when getting pushed into master. This is only for local dev
// make this into a map value 1 = terragrunt_working_dir
// value 2 = test location
def String terragrunt_working_dir = '/Users/bskeen/repository/appeals-terraform/live/uat/revproxy-caseflow-replica'

public Map get_outputs(terragrunt_working_dir) {
  	println 'Running get_outputs()'
	def String infra_set = 'common'
	def jsonSlurper = new JsonSlurper()
  	def init_sout = new StringBuilder(), init_serr = new StringBuilder()
	def proc_init =	"terragrunt init --terragrunt-source-update --terragrunt-working-dir ${terragrunt_working_dir}/${infra_set}".execute()
    proc_init.consumeProcessOutput(init_sout, init_serr) 
    proc_init.waitForOrKill(9000000)
	
	def sout = new StringBuilder(), serr = new StringBuilder()
  	def proc = "terragrunt output -json --terragrunt-source-update --terragrunt-working-dir ${terragrunt_working_dir}/${infra_set}".execute() 
  	proc.consumeProcessOutput(sout, serr) 
  	proc.waitForOrKill(9000000)
  	def object = jsonSlurper.parseText(sout.toString()) 
  	def Map outputs = [
	'attach_asg_to':object.get('attach_asg_to').get('value'),
  'blue_weight_a':object.get('blue_weight_a').get('value'), 
	'blue_weight_b':object.get('blue_weight_b').get('value'),
	'green_weight_a':object.get('green_weight_a').get('value'),
	'green_weight_b':object.get('green_weight_b').get('value'),
  	]
	return outputs
}

public def tg_apply(terragrunt_working_dir, infra_set) {
	println "Running tg_apply()"
	def apply_sout = new StringBuilder(), apply_serr = new StringBuilder()
	def proc_apply = "terragrunt apply -auto-approve --terragrunt-working-dir ${terragrunt_working_dir}/${infra_set}".execute() 
	proc_apply.consumeProcessOutput(apply_sout, apply_serr) 
	proc_apply.waitForOrKill(9000000)
	println "PROC_APPLY SERR = ${apply_serr}"
	println "PROC_APPLY SOUT = ${apply_sout}" 
}

public def tg_destroy(terragrunt_working_dir, infra_set) {
	println "Running tg_destroy()"
	def apply_sout = new StringBuilder(), apply_serr = new StringBuilder()
	def proc_apply = "terragrunt destroy -auto-approve --terragrunt-working-dir ${terragrunt_working_dir}/${infra_set}".execute() 
	proc_apply.consumeProcessOutput(apply_sout, apply_serr) 
	proc_apply.waitForOrKill(9000000)
	println "PROC_APPLY SERR = ${apply_serr}"
	println "PROC_APPLY SOUT = ${apply_sout}" 
}

// not sure if this is needed but might come in handly later
public def get_blue_green(terragrunt_working_dir) {	
	println "Running get_blue_green()"
	def Map outputs = get_outputs(terragrunt_working_dir)
	if (outputs.get('blue_weight_a').equals(100)) {
		blue = 'a'
	}
	else if (outputs.get('blue_weight_b').equals(100)) {
		blue = 'b'
	} 
	else {
		println "ERROR: Neither blue_weight_a or blue_weight_b is set to 100"
	}

	if (outputs.get('green_weight_a').equals(100)) {
		green = 'a'
	}
	else if (outputs.get('green_weight_b').equals(100)) {
		green = 'b'
	} 
	else {
		println "ERROR: Neither green_weight_a or green_weight_b is set to 100"
	}
	println "BLUE = ${blue}"
	println "GREEN = ${green}"
	return [blue, green]
}

public def change_attach_asg_to(terragrunt_working_dir) {
	def Map outputs = get_outputs(terragrunt_working_dir)
	println "Running change_attach_asg_to()"
	(blue, green) = get_blue_green(terragrunt_working_dir)	
	if (blue.compareTo('a').equals(0)) {
		attach_asg_to = 'b'
		println "ATTACHING TO ${attach_asg_to}"
	}
	else if (blue.compareTo('b').equals(0)) {
		attach_asg_to = 'a'
		println "ATTACHING TO ${attach_asg_to}"
	}
	def String infra_set = 'common'
	File tfvars = new File("${terragrunt_working_dir}/${infra_set}/terraform.tfvars")
	if (tfvars.canRead()) {
		tfvars.delete()
	}
	tfvars.append "attach_asg_to = \"${attach_asg_to}\"\n"
	tfvars.append "blue_weight_a = ${outputs.get('blue_weight_a')}\n"
	tfvars.append "blue_weight_b = ${outputs.get('blue_weight_b')}\n"
	tfvars.append "green_weight_a = ${outputs.get('green_weight_a')}\n"
	tfvars.append "green_weight_b = ${outputs.get('green_weight_b')}\n"
	tg_apply(terragrunt_working_dir, infra_set) // only changes the attach_asg_to var in common
	tfvars.delete()
}

public def deploy_green(terragrunt_working_dir) {
	println 'Running deploy_green()'
	(blue, green) = get_blue_green(terragrunt_working_dir)
	println "DEPLOYING ${green}"
	tg_apply(terragrunt_working_dir, green)
}

public def weight_shift(terragrunt_working_dir) {
	println 'Running weight_shift()'
	def String infra_set = 'common'
	(blue, green) = get_blue_green(terragrunt_working_dir)
	def Map outputs = get_outputs(terragrunt_working_dir)
	
	Integer x = 0
	Integer blue_weight_a = outputs.get('blue_weight_a')
	Integer blue_weight_b = outputs.get('blue_weight_b')

	while (x.compareTo(100).equals(-1)) {
		// blue weight shift starts here
		if (blue.compareTo('a').equals(0)) {
			blue_weight_a = blue_weight_a-10
			blue_weight_b = blue_weight_b+10
		}	
		else if (blue.compareTo('b').equals(0)) {
			blue_weight_a = blue_weight_a+10
			blue_weight_b = blue_weight_b-10
		}	
		
		File tfvars = new File("${terragrunt_working_dir}/${infra_set}/terraform.tfvars")
		if (tfvars.canRead()) {
			tfvars.delete()
		}
		tfvars.append "attach_asg_to = \"${outputs.get('attach_asg_to')}\"\n"
		tfvars.append "blue_weight_a = ${blue_weight_a}\n"
		tfvars.append "blue_weight_b = ${blue_weight_b}\n"
		tfvars.append "green_weight_a = ${outputs.get('green_weight_a')}\n"
		tfvars.append "green_weight_b = ${outputs.get('green_weight_b')}\n"
		tg_apply(terragrunt_working_dir, infra_set) // only changes the attach_asg_to var in common
		tfvars.delete()
		x = x+10
		sleep(10000)// sleeps for 10s
	}
	// TODO: Add in health check here before looping again
}

public def destroy_old_blue(terragrunt_working_dir) {
	println "Running destroy_old_blue()"
	(blue, green) = get_blue_green(terragrunt_working_dir)
	if (blue.compareTo('a').equals(0)) {
		old_blue = 'b'
	}
	else if (blue.compareTo('b').equals(0)) {
    	old_blue = 'a'
    }
	println "DESTROYING OLD BLUE ${old_blue}"
	tg_destroy(terragrunt_working_dir, old_blue)
}

public def update_green(terragrunt_working_dir) {
  println "Running update_green()"
  def String infra_set = 'common'
  (blue, green) = get_blue_green(terragrunt_working_dir)
  if (blue.compareTo('a').equals(0)) {
  	green_weight_a = 0 
    green_weight_b = 100
  }
  else if (blue.compareTo('b').equals(0)) {
    green_weight_a = 100 
    green_weight_b = 0
  }
  def Map outputs = get_outputs(terragrunt_working_dir)
  File tfvars = new File("${terragrunt_working_dir}/${infra_set}/terraform.tfvars")
  if (tfvars.canRead()) {
  	tfvars.delete()
  }
  tfvars.append "attach_asg_to = \"${outputs.get('attach_asg_to')}\"\n"
  tfvars.append "blue_weight_a = ${outputs.get('blue_weight_a')}\n"
  tfvars.append "blue_weight_b = ${outputs.get('blue_weight_b')}\n"
  tfvars.append "green_weight_a = ${green_weight_a}\n"
  tfvars.append "green_weight_b = ${green_weight_b}\n"
  tg_apply(terragrunt_working_dir, infra_set)
  tfvars.delete()
}




// Treat here and down as main()
// Jenkins pipeline would pass around vars in / out instead of this file 
println "Starting..."
change_attach_asg_to(terragrunt_working_dir)
deploy_green(terragrunt_working_dir)
weight_shift(terragrunt_working_dir)
destroy_old_blue(terragrunt_working_dir)
update_green(terragrunt_working_dir)
// TODO: it seems there is an issue with the state lock getting activated often times. Not too sure why
// However, everything does indeed work when ran individually
