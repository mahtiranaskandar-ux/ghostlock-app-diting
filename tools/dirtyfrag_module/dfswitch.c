// DirtyFrag module pipeline: the SELinux switch.
//
// The page-cache stub has already run in init. This module does the one
// kernel-side job the chain needs: set selinux_state.enforcing = 0, so the
// userspace Magisk late install can patch the policy (no domain has
// security:security load_policy under Enforcing). Read the address through
// the unexported kallsyms_lookup_name via kprobe, as the reference module.
#include <linux/kallsyms.h>
#include <linux/kprobes.h>
#include <linux/module.h>

static struct kprobe probe = { .symbol_name = "kallsyms_lookup_name" };

static int __init dfswitch_init(void)
{
	unsigned long (*lookup)(const char *name);
	unsigned long state;
	int ret = register_kprobe(&probe);

	pr_info("DFSWITCH register_kprobe ret=%d addr=%px\n", ret, probe.addr);
	if (ret)
		return ret;
	lookup = (unsigned long (*)(const char *))probe.addr;
	state = lookup("selinux_state");
	if (state) {
		*(volatile unsigned char *)state = 0;
		pr_info("DFSWITCH selinux_state=%px enforcing=%d\n", (void *)state,
			(int)*(volatile unsigned char *)state);
	} else {
		pr_info("DFSWITCH selinux_state not found\n");
	}
	unregister_kprobe(&probe);
	return state ? 0 : -ENOENT;
}

static void __exit dfswitch_exit(void)
{
	pr_info("DFSWITCH exit\n");
}

module_init(dfswitch_init);
module_exit(dfswitch_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("DirtyFrag SELinux switch");
