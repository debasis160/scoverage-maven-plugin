package pkg01

import org.junit.Test;
import org.junit.Assert.assertEquals

class HelloServiceTest
{
    @Test
    def test1()
    {
        assertEquals("Hello from module 2", HelloService1.hello)
    }

}
