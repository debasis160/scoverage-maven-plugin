package pkg02_01

import org.junit.Test;
import org.junit.Assert.assertEquals

class HelloServiceTest
{
    @Test
    def test2()
    {
        assertEquals("Hello from submodule02_01", HelloService2.hello)
    }

}
